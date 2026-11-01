/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

object PlaylistCacheScheduler {
    const val INPUT_JOB_KEY = "playlist_cache_job_key"
    const val INPUT_SERVICE_ID = "playlist_cache_service_id"
    const val INPUT_PLAYLIST_URL = "playlist_cache_playlist_url"
    const val INPUT_IS_LOCAL = "playlist_cache_is_local"
    const val INPUT_LOCAL_PLAYLIST_ID = "playlist_cache_local_playlist_id"
    const val INPUT_PLAYLIST_NAME = "playlist_cache_playlist_name"
    const val INPUT_THUMBNAIL_URL = "playlist_cache_thumbnail_url"

    const val PROGRESS_COMPLETED = "playlist_cache_completed"
    const val PROGRESS_TOTAL = "playlist_cache_total"
    const val PROGRESS_TITLE = "playlist_cache_title"

    private const val WORK_PREFIX = "playlist_cache_work_"

    @JvmStatic
    fun remoteJobKey(serviceId: Int, playlistUrl: String): String {
        return "remote:$serviceId:$playlistUrl"
    }

    @JvmStatic
    fun localJobKey(localPlaylistId: Long): String {
        return "local:$localPlaylistId"
    }

    @JvmStatic
    fun enqueueRemote(
        context: Context,
        serviceId: Int,
        playlistUrl: String,
        playlistName: String,
        thumbnailUrl: String?,
        replace: Boolean = false
    ): String {
        val jobKey = remoteJobKey(serviceId, playlistUrl)
        enqueue(
            context = context,
            jobKey = jobKey,
            serviceId = serviceId,
            playlistUrl = playlistUrl,
            isLocal = false,
            localPlaylistId = null,
            playlistName = playlistName,
            thumbnailUrl = thumbnailUrl,
            replace = replace
        )
        return jobKey
    }

    @JvmStatic
    fun enqueueLocal(
        context: Context,
        localPlaylistId: Long,
        playlistName: String,
        thumbnailUrl: String?,
        replace: Boolean = false
    ): String {
        val jobKey = localJobKey(localPlaylistId)
        enqueue(
            context = context,
            jobKey = jobKey,
            serviceId = -1,
            playlistUrl = "",
            isLocal = true,
            localPlaylistId = localPlaylistId,
            playlistName = playlistName,
            thumbnailUrl = thumbnailUrl,
            replace = replace
        )
        return jobKey
    }

    @JvmStatic
    fun cancel(context: Context, jobKey: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(jobKey))
    }

    @JvmStatic
    fun workName(jobKey: String): String {
        return WORK_PREFIX + jobKey.hashCode().toUInt().toString(16)
    }

    private fun enqueue(
        context: Context,
        jobKey: String,
        serviceId: Int,
        playlistUrl: String,
        isLocal: Boolean,
        localPlaylistId: Long?,
        playlistName: String,
        thumbnailUrl: String?,
        replace: Boolean
    ) {
        val data = workDataOf(
            INPUT_JOB_KEY to jobKey,
            INPUT_SERVICE_ID to serviceId,
            INPUT_PLAYLIST_URL to playlistUrl,
            INPUT_IS_LOCAL to isLocal,
            INPUT_LOCAL_PLAYLIST_ID to (localPlaylistId ?: -1L),
            INPUT_PLAYLIST_NAME to playlistName,
            INPUT_THUMBNAIL_URL to (thumbnailUrl ?: "")
        )
        val request = OneTimeWorkRequestBuilder<PlaylistCacheWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(jobKey), policy, request)
    }
}
