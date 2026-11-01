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
import us.shandian.giga.get.DownloadMission

/**
 * Receives Giga mission lifecycle events and updates the playlist cache database.
 *
 * The bridge is intentionally outside Giga's download logic. Giga keeps ownership of bytes;
 * this class only updates the cache bookkeeping when a mission owns one of our target URIs.
 */
object PlaylistCacheGigaBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JvmStatic
    fun onMissionFinished(context: Context, mission: DownloadMission) {
        val targetUri = mission.storageUriOrNull() ?: return
        scope.launch {
            PlaylistCacheManager(context.applicationContext)
                .markTrackCompleteByTargetUri(targetUri)
        }
    }

    @JvmStatic
    fun onMissionError(context: Context, mission: DownloadMission) {
        val targetUri = mission.storageUriOrNull() ?: return
        val terminal = !mission.enqueued
        scope.launch {
            val manager = PlaylistCacheManager(context.applicationContext)
            if (terminal) {
                manager.markTrackFailedByTargetUri(
                    targetUri,
                    "Download failed with code ${mission.errCode}"
                )
            }
        }
    }

    private fun DownloadMission.storageUriOrNull(): String? {
        return runCatching { storage?.uri?.toString() }.getOrNull()
    }
}
