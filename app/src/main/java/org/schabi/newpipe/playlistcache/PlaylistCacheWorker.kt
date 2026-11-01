/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.download.AudioPostprocessingHelper
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.player.resolver.PlaybackResolver
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.ListHelper
import org.schabi.newpipe.util.image.ImageStrategy
import us.shandian.giga.get.MissionRecoveryInfo
import us.shandian.giga.service.DownloadManager
import us.shandian.giga.service.DownloadManagerService

/**
 * Playlist-level cache job.
 *
 * This worker enumerates tracks, resolves the best audio stream for each track, and schedules
 * Giga missions. Giga performs the actual byte-level downloads, resumption and postprocessing.
 */
class PlaylistCacheWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    private val targetUris: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(0, 0, applicationContext.getString(R.string.app_name))
    }

    override suspend fun doWork(): Result {
        val jobKey = inputData.getString(PlaylistCacheScheduler.INPUT_JOB_KEY)
            ?: return Result.failure()
        val serviceId = inputData.getInt(PlaylistCacheScheduler.INPUT_SERVICE_ID, -1)
        val playlistUrl = inputData.getString(PlaylistCacheScheduler.INPUT_PLAYLIST_URL).orEmpty()
        val isLocal = inputData.getBoolean(PlaylistCacheScheduler.INPUT_IS_LOCAL, false)
        val localPlaylistId = inputData
            .getLong(PlaylistCacheScheduler.INPUT_LOCAL_PLAYLIST_ID, -1L)
            .takeIf { it >= 0 }
        val playlistName = inputData
            .getString(PlaylistCacheScheduler.INPUT_PLAYLIST_NAME)
            .orEmpty()
        val thumbnailUrl = inputData.getString(PlaylistCacheScheduler.INPUT_THUMBNAIL_URL)

        val manager = PlaylistCacheManager(applicationContext)
        val job = manager.getOrCreateJob(
            jobKey = jobKey,
            serviceId = serviceId,
            playlistUrl = playlistUrl,
            isLocal = isLocal,
            localPlaylistId = localPlaylistId,
            playlistName = playlistName,
            thumbnailUrl = thumbnailUrl
        )
        manager.markJobRunning(job.id)
        setForeground(createForegroundInfo(0, 0, playlistName))

        val items = try {
            withContext(Dispatchers.IO) {
                if (isLocal) {
                    loadLocalTracks(localPlaylistId ?: -1L)
                } else {
                    loadRemoteTracks(serviceId, playlistUrl)
                }
            }
        } catch (e: Exception) {
            manager.markJobFailed(job.id)
            return Result.failure()
        }

        val tracks = manager.seedTracks(
            jobId = job.id,
            tracks = items.mapIndexed { index, item -> item.toTrackEntity(job.id, index) }
        )
        if (tracks.isEmpty()) {
            manager.markJobFailed(job.id)
            return Result.failure()
        }

        val missionController = GigaMissionController(applicationContext)
        if (!missionController.connect()) {
            manager.markJobFailed(job.id)
            return Result.retry()
        }

        try {
            val existingTargets = missionController.missionProgress().keys
            for (track in manager.getTracks(job.id)) {
                if (isStopped) {
                    cancelRemaining(manager, job.id, missionController)
                    return Result.failure()
                }
                if (track.state == PlaylistCacheTrackState.COMPLETE.name ||
                    track.state == PlaylistCacheTrackState.SKIPPED.name
                ) {
                    continue
                }
                if (track.state == PlaylistCacheTrackState.SCHEDULED.name ||
                    track.state == PlaylistCacheTrackState.DOWNLOADING.name
                ) {
                    val uri = track.targetUri
                    if (uri != null && uri in existingTargets) {
                        targetUris.add(uri)
                        continue
                    }
                }
                scheduleTrack(manager, track)
            }

            awaitCompletion(manager, job.id, missionController, playlistName)
            manager.refreshJobProgress(job.id)
            return Result.success()
        } finally {
            missionController.disconnect()
        }
    }

    private suspend fun scheduleTrack(
        manager: PlaylistCacheManager,
        track: PlaylistCacheTrackEntity
    ) {
        manager.markTrackResolving(track.id)
        val info: StreamInfo = try {
            ExtractorHelper.getStreamInfo(track.serviceId, track.streamUrl, false).await()
        } catch (e: Exception) {
            manager.markTrackFailedById(track.id, e.message)
            return
        }
        if (info.streamType == StreamType.LIVE_STREAM ||
            info.streamType == StreamType.POST_LIVE_STREAM
        ) {
            manager.markTrackFailedById(track.id, "Live streams are not cached")
            return
        }

        val audioStreams = ListHelper.getFilteredAudioStreams(
            applicationContext,
            info.audioStreams
        )
        val audioIndex = ListHelper.getAudioFormatIndex(applicationContext, audioStreams, null)
        val audioStream = audioStreams.getOrNull(audioIndex)
        if (audioStream == null ||
            !audioStream.isUrl ||
            audioStream.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP
        ) {
            manager.markTrackFailedById(track.id, "No progressive audio stream found")
            return
        }

        val content = audioStream.content
        if (content.isEmpty()) {
            manager.markTrackFailedById(track.id, "Audio stream has no content URL")
            return
        }

        val cacheKey = PlaybackResolver.cacheKeyOf(info, audioStream)
        if (manager.getCompleteCachedAudio(cacheKey) != null) {
            manager.markTrackSkipped(track.id, cacheKey)
            return
        }

        val suffix = AudioPostprocessingHelper.outputSuffix(audioStream)
        val part = manager.storage.partFile(cacheKey, suffix)
        val prepared = try {
            manager.storage.createTargetFile(part)
            manager.prepareTrackForDownload(
                trackId = track.id,
                cacheKey = cacheKey,
                audioFormat = suffix,
                audioBitrate = audioStream.averageBitrate,
                audioTrackId = audioStream.audioTrackId,
                streamInfo = info,
                targetFile = part
            )
        } catch (e: Exception) {
            manager.markTrackFailedById(track.id, e.message)
            return
        }
        if (!prepared) {
            manager.markTrackFailedById(track.id, "Could not prepare cache target")
            return
        }

        val targetUri = Uri.fromFile(part).toString()
        targetUris.add(targetUri)
        try {
            val storage = StoredFileHelper(
                applicationContext,
                null,
                Uri.fromFile(part),
                DownloadManager.TAG_AUDIO
            )
            DownloadManagerService.startMission(
                applicationContext,
                arrayOf(content),
                storage,
                'a',
                DEFAULT_DOWNLOAD_THREADS,
                info,
                AudioPostprocessingHelper.postprocessingName(audioStream),
                null,
                0L,
                arrayListOf(MissionRecoveryInfo(audioStream))
            )
        } catch (e: Exception) {
            manager.markTrackFailedByTargetUri(targetUri, e.message)
        }
    }

    private suspend fun awaitCompletion(
        manager: PlaylistCacheManager,
        jobId: Long,
        missionController: GigaMissionController,
        playlistName: String
    ) {
        while (!isStopped) {
            val progress = missionController.missionProgress()
            val tracks = manager.getTracks(jobId)
            for (track in tracks) {
                val uri = track.targetUri ?: continue
                val mission = progress[uri] ?: continue
                manager.updateTrackProgress(uri, mission.downloadedBytes, mission.totalBytes)
            }
            manager.refreshJobProgress(jobId)

            val refreshed = manager.getTracks(jobId)
            val completed = refreshed.count {
                it.state == PlaylistCacheTrackState.COMPLETE.name ||
                    it.state == PlaylistCacheTrackState.SKIPPED.name
            }
            val terminal = refreshed.count { isTerminal(it.state) }
            val currentTitle = refreshed.firstOrNull {
                it.state == PlaylistCacheTrackState.DOWNLOADING.name
            }?.title ?: playlistName

            setProgress(
                workDataOf(
                    PlaylistCacheScheduler.PROGRESS_COMPLETED to completed,
                    PlaylistCacheScheduler.PROGRESS_TOTAL to refreshed.size,
                    PlaylistCacheScheduler.PROGRESS_TITLE to currentTitle
                )
            )
            setForeground(createForegroundInfo(completed, refreshed.size, currentTitle))

            if (terminal >= refreshed.size) {
                return
            }
            delay(PROGRESS_POLL_INTERVAL_MILLIS)
        }
        cancelRemaining(manager, jobId, missionController)
    }

    private suspend fun cancelRemaining(
        manager: PlaylistCacheManager,
        jobId: Long,
        missionController: GigaMissionController
    ) {
        missionController.cancelMissions(targetUris)
        manager.getTracks(jobId).forEach { track ->
            if (!isTerminal(track.state)) {
                manager.markTrackCanceled(track.id)
            }
        }
        manager.markJobCanceled(jobId)
    }

    private fun isTerminal(state: String): Boolean {
        return state == PlaylistCacheTrackState.COMPLETE.name ||
            state == PlaylistCacheTrackState.SKIPPED.name ||
            state == PlaylistCacheTrackState.FAILED.name ||
            state == PlaylistCacheTrackState.CANCELED.name
    }

    private suspend fun loadRemoteTracks(
        serviceId: Int,
        playlistUrl: String
    ): List<StreamInfoItem> {
        val first = ExtractorHelper.getPlaylistInfo(serviceId, playlistUrl, false).await()
        val result = mutableListOf<StreamInfoItem>()
        result.addAll(first.relatedItems.filterIsInstance<StreamInfoItem>())

        var nextPage: Page? = first.nextPage
        while (Page.isValid(nextPage)) {
            val page = ExtractorHelper.getMorePlaylistItems(
                serviceId,
                playlistUrl,
                nextPage
            ).await()
            result.addAll(page.items.filterIsInstance<StreamInfoItem>())
            nextPage = page.nextPage
        }
        return result.distinctBy { it.url }
    }

    private fun loadLocalTracks(localPlaylistId: Long): List<StreamInfoItem> {
        return LocalPlaylistManager(NewPipeDatabase.getInstance(applicationContext))
            .getPlaylistStreams(localPlaylistId)
            .blockingFirst()
            .map { it.toStreamInfoItem() }
    }

    private fun StreamInfoItem.toTrackEntity(
        jobId: Long,
        position: Int
    ): PlaylistCacheTrackEntity {
        return PlaylistCacheTrackEntity(
            jobId = jobId,
            position = position,
            serviceId = serviceId,
            streamUrl = url,
            title = name,
            uploader = uploaderName ?: "",
            uploaderUrl = uploaderUrl,
            durationSeconds = duration,
            thumbnailUrl = ImageStrategy.imageListToDbUrl(thumbnails),
            streamType = streamType.name
        )
    }

    private fun createForegroundInfo(
        completed: Int,
        total: Int,
        title: String
    ): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val indeterminate = total <= 0
        val notification: Notification = NotificationCompat.Builder(
            applicationContext,
            applicationContext.getString(R.string.notification_channel_id)
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.downloads_title))
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(0), completed.coerceAtLeast(0), indeterminate)
            .addAction(0, applicationContext.getString(android.R.string.cancel), cancelIntent)
            .build()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        return ForegroundInfo(NOTIFICATION_ID, notification, serviceType)
    }

    companion object {
        private const val NOTIFICATION_ID = 0xCA5E1
        private const val DEFAULT_DOWNLOAD_THREADS = 3
        private const val PROGRESS_POLL_INTERVAL_MILLIS = 1_500L
    }
}
