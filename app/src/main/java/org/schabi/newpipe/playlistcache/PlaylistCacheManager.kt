/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import android.content.Context
import android.net.Uri
import androidx.room3.withWriteTransaction
import java.io.File
import kotlinx.coroutines.flow.Flow
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Coordinates playlist cache jobs, shared audio entries, and reference-aware deletion.
 */
class PlaylistCacheManager(context: Context) {
    private val appContext = context.applicationContext
    private val database = PlaylistCacheDatabase.getInstance(appContext)
    private val dao = database.cacheDao()
    val storage = PlaylistCacheStorage(appContext)

    fun observeJob(jobKey: String): Flow<PlaylistCacheJobEntity?> = dao.observeJobByKey(jobKey)

    fun observeJobs(): Flow<List<PlaylistCacheJobEntity>> = dao.observeJobs()

    fun observeTracks(jobId: Long): Flow<List<PlaylistCacheTrackEntity>> =
        dao.observeTracksForJob(jobId)

    suspend fun getJobByKey(jobKey: String): PlaylistCacheJobEntity? = dao.getJobByKey(jobKey)

    suspend fun getJobById(jobId: Long): PlaylistCacheJobEntity? = dao.getJobById(jobId)

    suspend fun getTracks(jobId: Long): List<PlaylistCacheTrackEntity> =
        dao.getTracksForJob(jobId)

    suspend fun getCompleteTracks(jobId: Long): List<PlaylistCacheTrackEntity> =
        dao.getTracksForJobByStates(
            jobId,
            listOf(
                PlaylistCacheTrackState.COMPLETE.name,
                PlaylistCacheTrackState.SKIPPED.name,
            ),
        )

    suspend fun getOrCreateJob(
        jobKey: String,
        serviceId: Int,
        playlistUrl: String,
        isLocal: Boolean,
        localPlaylistId: Long?,
        playlistName: String,
        thumbnailUrl: String?,
    ): PlaylistCacheJobEntity {
        dao.getJobByKey(jobKey)?.let { return it }

        val entity = PlaylistCacheJobEntity(
            jobKey = jobKey,
            serviceId = serviceId,
            playlistUrl = playlistUrl,
            isLocal = isLocal,
            localPlaylistId = localPlaylistId,
            playlistName = playlistName,
            thumbnailUrl = thumbnailUrl,
        )
        val id = dao.insertJob(entity)
        if (id >= 0) {
            return entity.copy(id = id)
        }
        return checkNotNull(dao.getJobByKey(jobKey)) {
            "Could not create or load playlist cache job $jobKey"
        }
    }

    /**
     * Seeds the ordered track list only when the job has no tracks yet. Re-running a job must not
     * discard completed progress.
     */
    suspend fun seedTracks(
        jobId: Long,
        tracks: List<PlaylistCacheTrackEntity>,
    ): List<PlaylistCacheTrackEntity> {
        val existing = dao.getTracksForJob(jobId)
        if (existing.isNotEmpty() || tracks.isEmpty()) {
            return existing
        }

        val ids = dao.insertTracks(tracks)
        val job = dao.getJobById(jobId) ?: return emptyList()
        job.totalTracks = ids.size
        job.updatedAt = System.currentTimeMillis()
        dao.updateJob(job)
        return dao.getTracksForJob(jobId)
    }

    /**
     * Marks a track as scheduled, writes its offline metadata, and records the Giga target URI.
     * The metadata is written before the download starts so completion does not need the
     * StreamInfo again.
     */
    suspend fun prepareTrackForDownload(
        trackId: Long,
        cacheKey: String,
        audioFormat: String?,
        audioBitrate: Int,
        audioTrackId: String?,
        streamInfo: StreamInfo,
        targetFile: File,
    ): Boolean {
        val metadataFile = storage.writeStreamInfo(cacheKey, streamInfo) ?: return false
        val track = dao.getTrackById(trackId) ?: return false
        track.cacheKey = cacheKey
        track.audioFormat = audioFormat
        track.audioBitrate = audioBitrate
        track.audioTrackId = audioTrackId
        track.targetUri = Uri.fromFile(targetFile).toString()
        track.state = PlaylistCacheTrackState.SCHEDULED.name
        track.error = null
        track.updatedAt = System.currentTimeMillis()
        dao.updateTrack(track)
        check(metadataFile.isFile)
        refreshJobProgress(track.jobId)
        return true
    }

    suspend fun getTrackByTargetUri(targetUri: String): PlaylistCacheTrackEntity? =
        dao.getTrackByTargetUri(targetUri)

    suspend fun markTrackSkipped(trackId: Long, cacheKey: String) {
        val track = dao.getTrackById(trackId) ?: return
        track.cacheKey = cacheKey
        track.state = PlaylistCacheTrackState.SKIPPED.name
        track.error = null
        track.updatedAt = System.currentTimeMillis()
        dao.updateTrack(track)
        refreshJobProgress(track.jobId)
    }

    suspend fun markTrackFailedById(trackId: Long, error: String?) {
        val track = dao.getTrackById(trackId) ?: return
        markTrackFailed(track, error)
    }

    suspend fun markTrackResolving(trackId: Long) {
        val track = dao.getTrackById(trackId) ?: return
        track.state = PlaylistCacheTrackState.RESOLVING.name
        track.error = null
        track.updatedAt = System.currentTimeMillis()
        dao.updateTrack(track)
    }

    suspend fun updateTrackProgress(
        targetUri: String,
        downloadedBytes: Long,
        totalBytes: Long,
    ) {
        val track = dao.getTrackByTargetUri(targetUri) ?: return
        if (track.state == PlaylistCacheTrackState.COMPLETE.name ||
            track.state == PlaylistCacheTrackState.CANCELED.name
        ) {
            return
        }
        track.state = PlaylistCacheTrackState.DOWNLOADING.name
        track.downloadedBytes = downloadedBytes
        track.totalBytes = totalBytes
        track.updatedAt = System.currentTimeMillis()
        dao.updateTrack(track)
        refreshJobProgress(track.jobId)
    }

    /**
     * Finalizes a completed Giga download and promotes it into the shared cached-audio table.
     */
    suspend fun markTrackCompleteByTargetUri(targetUri: String): Boolean {
        val track = dao.getTrackByTargetUri(targetUri) ?: return false
        val cacheKey = track.cacheKey ?: return false
        val part = storage.partFile(cacheKey, track.audioFormat)
        val finalFile = storage.finalFile(cacheKey, track.audioFormat)

        if (!storage.isUsable(part) && !storage.isUsable(finalFile)) {
            markTrackFailed(track, "Downloaded cache file is missing or empty")
            return false
        }
        if (storage.isUsable(part) && !storage.finalizeDownload(part, finalFile)) {
            markTrackFailed(track, "Could not finalize cached audio file")
            return false
        }
        if (!storage.isUsable(finalFile)) {
            markTrackFailed(track, "Finalized cache file is not readable")
            return false
        }

        val audio = CachedAudioEntity(
            cacheKey = cacheKey,
            filePath = finalFile.absolutePath,
            metadataPath = storage.metadataFile(cacheKey).absolutePath,
            format = track.audioFormat,
            bitrate = track.audioBitrate,
            audioTrackId = track.audioTrackId,
            sizeBytes = finalFile.length(),
            metadataVersion = PlaylistCacheStorage.METADATA_VERSION,
        )

        database.withWriteTransaction {
            dao.insertCachedAudio(audio)
            track.state = PlaylistCacheTrackState.COMPLETE.name
            track.downloadedBytes = finalFile.length()
            track.targetUri = null
            track.error = null
            track.updatedAt = System.currentTimeMillis()
            dao.updateTrack(track)
        }
        refreshJobProgress(track.jobId)
        return true
    }

    suspend fun markTrackFailedByTargetUri(targetUri: String, error: String?) {
        val track = dao.getTrackByTargetUri(targetUri) ?: return
        markTrackFailed(track, error)
    }

    suspend fun markTrackCanceled(trackId: Long) {
        val track = dao.getTrackById(trackId) ?: return
        track.state = PlaylistCacheTrackState.CANCELED.name
        track.error = null
        track.updatedAt = System.currentTimeMillis()
        dao.updateTrack(track)
        refreshJobProgress(track.jobId)
    }

    suspend fun markTrackPending(trackId: Long) {
        val track = dao.getTrackById(trackId) ?: return
        track.state = PlaylistCacheTrackState.PENDING.name
        track.targetUri = null
        track.downloadedBytes = 0
        track.error = null
        track.updatedAt = System.currentTimeMillis()
        dao.updateTrack(track)
        refreshJobProgress(track.jobId)
    }

    private suspend fun markTrackFailed(track: PlaylistCacheTrackEntity, error: String?) {
        track.state = PlaylistCacheTrackState.FAILED.name
        track.error = error
        track.updatedAt = System.currentTimeMillis()
        dao.updateTrack(track)
        refreshJobProgress(track.jobId)
    }

    suspend fun refreshJobProgress(jobId: Long) {
        val job = dao.getJobById(jobId) ?: return
        val tracks = dao.getTracksForJob(jobId)
        if (tracks.isEmpty()) {
            return
        }

        val completed = tracks.count {
            it.state == PlaylistCacheTrackState.COMPLETE.name ||
                it.state == PlaylistCacheTrackState.SKIPPED.name
        }
        val failed = tracks.count { it.state == PlaylistCacheTrackState.FAILED.name }
        val canceled = tracks.count { it.state == PlaylistCacheTrackState.CANCELED.name }
        job.totalTracks = tracks.size
        job.completedTracks = completed
        job.failedTracks = failed
        job.state = when {
            completed + failed + canceled >= tracks.size && failed > 0 ->
                PlaylistCacheJobState.PARTIAL.name

            completed + failed + canceled >= tracks.size && canceled > 0 ->
                PlaylistCacheJobState.CANCELED.name

            completed + failed + canceled >= tracks.size ->
                PlaylistCacheJobState.COMPLETE.name

            else -> PlaylistCacheJobState.RUNNING.name
        }
        job.updatedAt = System.currentTimeMillis()
        dao.updateJob(job)
    }

    suspend fun markJobRunning(jobId: Long) {
        val job = dao.getJobById(jobId) ?: return
        job.state = PlaylistCacheJobState.RUNNING.name
        job.updatedAt = System.currentTimeMillis()
        dao.updateJob(job)
    }

    suspend fun markJobCanceled(jobId: Long) {
        val job = dao.getJobById(jobId) ?: return
        job.state = PlaylistCacheJobState.CANCELED.name
        job.updatedAt = System.currentTimeMillis()
        dao.updateJob(job)
    }

    suspend fun markJobFailed(jobId: Long) {
        val job = dao.getJobById(jobId) ?: return
        job.state = PlaylistCacheJobState.FAILED.name
        job.updatedAt = System.currentTimeMillis()
        dao.updateJob(job)
    }

    suspend fun invalidateCachedAudio(cacheKey: String) {
        val audio = dao.getCachedAudio(cacheKey) ?: return
        dao.deleteCachedAudio(cacheKey)
        storage.deleteQuietly(File(audio.filePath))
        audio.metadataPath?.let { storage.deleteQuietly(File(it)) }
    }

    /**
     * Returns a complete, readable shared cache entry for the given playback cache key.
     */
    suspend fun getCompleteCachedAudio(cacheKey: String): CachedAudioEntity? {
        val audio = dao.getCachedAudio(cacheKey) ?: return null
        val file = File(audio.filePath)
        if (!storage.isUsable(file)) {
            database.withWriteTransaction {
                dao.deleteCachedAudio(cacheKey)
            }
            return null
        }
        return audio
    }

    /**
     * Returns persisted StreamInfo for offline playback, or null when no valid metadata exists.
     */
    suspend fun getOfflineStreamInfo(serviceId: Int, streamUrl: String): StreamInfo? {
        val track = dao.getTrackForStreamByStates(
            serviceId,
            streamUrl,
            listOf(
                PlaylistCacheTrackState.COMPLETE.name,
                PlaylistCacheTrackState.SKIPPED.name,
            ),
        ) ?: return null
        val cacheKey = track.cacheKey ?: return null
        val audio = dao.getCachedAudio(cacheKey) ?: return null
        if (audio.metadataVersion != PlaylistCacheStorage.METADATA_VERSION) {
            return null
        }
        if (!storage.isUsable(File(audio.filePath))) {
            return null
        }
        return storage.readStreamInfo(cacheKey)
    }

    /**
     * Removes one playlist's cache references. Physical files are deleted only when no other
     * playlist references the same cache key.
     *
     * @return the number of physical files deleted.
     */
    suspend fun removePlaylistCache(jobId: Long): Int {
        val tracks = dao.getTracksForJob(jobId)
        val cacheKeys = tracks.mapNotNull { it.cacheKey }.toSet()
        val filesToDelete = mutableListOf<File>()

        database.withWriteTransaction {
            dao.deleteJob(jobId)
            for (cacheKey in cacheKeys) {
                if (dao.countCacheReferences(cacheKey) == 0) {
                    val audio = dao.getCachedAudio(cacheKey)
                    if (audio != null) {
                        filesToDelete.add(File(audio.filePath))
                        audio.metadataPath?.let { filesToDelete.add(File(it)) }
                    }
                    dao.deleteCachedAudio(cacheKey)
                }
            }
        }

        // Incomplete targets do not belong to a shared entry and are safe to remove with the job.
        tracks.mapNotNull { it.targetUri }.forEach { uri ->
            filesToDelete.add(File(Uri.parse(uri).path.orEmpty()))
        }

        var deleted = 0
        for (file in filesToDelete.distinct()) {
            if (file.isFile && file.delete()) {
                deleted++
            }
        }
        return deleted
    }

    /**
     * Reconciles jobs left in a running state after process death. Completed files are promoted,
     * while missing in-progress targets are reset for a future retry.
     */
    suspend fun reconcileJobs() {
        for (job in dao.getJobs()) {
            if (job.state != PlaylistCacheJobState.RUNNING.name &&
                job.state != PlaylistCacheJobState.QUEUED.name
            ) {
                continue
            }
            val tracks = dao.getTracksForJob(job.id)
            for (track in tracks) {
                if (track.state == PlaylistCacheTrackState.COMPLETE.name ||
                    track.state == PlaylistCacheTrackState.SKIPPED.name ||
                    track.state == PlaylistCacheTrackState.FAILED.name ||
                    track.state == PlaylistCacheTrackState.CANCELED.name
                ) {
                    continue
                }
                val targetUri = track.targetUri
                if (targetUri == null) {
                    continue
                }
                val targetPath = Uri.parse(targetUri).path
                val targetFile = targetPath?.let { File(it) }
                val cacheKey = track.cacheKey
                if (cacheKey != null) {
                    val finalFile = storage.finalFile(cacheKey, track.audioFormat)
                    if (storage.isUsable(finalFile)) {
                        markTrackCompleteByTargetUri(targetUri)
                        continue
                    }
                    if (storage.isUsable(targetFile) &&
                        track.totalBytes > 0 &&
                        targetFile?.length() == track.totalBytes
                    ) {
                        // Giga finished but the process died before the completion bridge ran.
                        markTrackCompleteByTargetUri(targetUri)
                        continue
                    }
                }
                if (targetFile == null || !targetFile.exists()) {
                    markTrackPending(track.id)
                }
            }
            refreshJobProgress(job.id)
        }
    }

    suspend fun cleanupOrphans() {
        val referenced = mutableSetOf<String>()
        val staleFiles = mutableListOf<File>()
        val allAudio = dao.getAllCachedAudio()
        database.withWriteTransaction {
            for (audio in allAudio) {
                val file = File(audio.filePath)
                referenced.add(file.absolutePath)
                audio.metadataPath?.let { referenced.add(File(it).absolutePath) }
                val fileOk = storage.isUsable(file)
                val refs = dao.countCacheReferences(audio.cacheKey)
                if (!fileOk || refs == 0) {
                    staleFiles.add(file)
                    audio.metadataPath?.let { staleFiles.add(File(it)) }
                    dao.deleteCachedAudio(audio.cacheKey)
                }
            }
        }

        staleFiles.forEach { storage.deleteQuietly(it) }
        storage.listAudioFiles().forEach { file ->
            if (file.absolutePath !in referenced && file.extension == "part") {
                file.delete()
            }
        }
    }
}
