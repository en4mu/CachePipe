/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * Small UI-facing operations for playlist cache actions. Keeps fragments Java-friendly and
 * keeps database work off the main thread.
 */
object PlaylistCacheUi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @JvmStatic
    fun confirmRemoveCachedAudio(
        activity: Activity,
        jobKey: String,
        playlistName: String,
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.remove_cached_audio)
            .setMessage(activity.getString(R.string.remove_cached_audio_confirm, playlistName))
            .setPositiveButton(R.string.remove_cached_audio) { _, _ ->
                removeCachedAudio(activity, jobKey)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @JvmStatic
    fun removeCachedAudio(context: Context, jobKey: String) {
        PlaylistCacheScheduler.cancel(context, jobKey)
        scope.launch {
            val manager = PlaylistCacheManager(context.applicationContext)
            val job = withContext(Dispatchers.IO) { manager.getJobByKey(jobKey) }
            if (job == null) {
                Toast.makeText(
                    context,
                    R.string.no_cached_tracks,
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            val deleted = withContext(Dispatchers.IO) {
                manager.removePlaylistCache(job.id)
            }
            Toast.makeText(
                context,
                context.getString(R.string.cached_audio_removed, deleted),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    @JvmStatic
    fun playCached(context: Context, jobKey: String) {
        scope.launch {
            val manager = PlaylistCacheManager(context.applicationContext)
            val job = withContext(Dispatchers.IO) { manager.getJobByKey(jobKey) }
            if (job == null) {
                Toast.makeText(context, R.string.no_cached_tracks, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val tracks = withContext(Dispatchers.IO) { manager.getCompleteTracks(job.id) }
            if (tracks.isEmpty()) {
                Toast.makeText(context, R.string.no_cached_tracks, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val items = tracks.map { track ->
                StreamInfoItem(
                    track.serviceId,
                    track.streamUrl,
                    track.title,
                    runCatching { StreamType.valueOf(track.streamType) }
                        .getOrDefault(StreamType.VIDEO_STREAM),
                ).apply {
                    duration = track.durationSeconds
                    uploaderName = track.uploader
                    uploaderUrl = track.uploaderUrl
                    thumbnails = ImageStrategy.dbUrlToImageList(track.thumbnailUrl)
                }
            }
            NavigationHelper.playOnBackgroundPlayer(
                context,
                SinglePlayQueue(items, 0),
                true,
            )
        }
    }

    @JvmStatic
    fun cancelCaching(context: Context, jobKey: String) {
        PlaylistCacheScheduler.cancel(context, jobKey)
        Toast.makeText(context, R.string.caching_canceled, Toast.LENGTH_SHORT).show()
    }
}
