/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Java-friendly playback entry points for the playlist cache.
 *
 * The blocking lookup methods are intentionally called from playback and extraction IO paths.
 */
object PlaylistCachePlayback {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun findCompleteAudio(context: Context, cacheKey: String): CachedAudioEntity? {
        return runBlocking(Dispatchers.IO) {
            PlaylistCacheManager(context).getCompleteCachedAudio(cacheKey)
        }
    }

    @JvmStatic
    fun findOfflineStreamInfo(
        context: Context,
        serviceId: Int,
        streamUrl: String
    ): StreamInfo? {
        return runBlocking(Dispatchers.IO) {
            PlaylistCacheManager(context).getOfflineStreamInfo(serviceId, streamUrl)
        }
    }

    @JvmStatic
    fun invalidate(context: Context, cacheKey: String) {
        scope.launch {
            PlaylistCacheManager(context.applicationContext).invalidateCachedAudio(cacheKey)
        }
    }

    /**
     * Invalidates a broken cache entry and then runs [action] on the main thread.
     */
    @JvmStatic
    fun invalidateAndRun(context: Context, cacheKey: String, action: Runnable) {
        scope.launch {
            PlaylistCacheManager(context.applicationContext).invalidateCachedAudio(cacheKey)
            withContext(Dispatchers.Main) {
                action.run()
            }
        }
    }
}
