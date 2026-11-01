/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import us.shandian.giga.get.DownloadMission
import us.shandian.giga.service.DownloadManager
import us.shandian.giga.service.DownloadManagerService
import us.shandian.giga.service.DownloadManagerService.DownloadManagerBinder

/**
 * Thin read/control bridge over Giga's existing DownloadManager.
 *
 * Giga remains responsible for byte-level downloads, retries, resumption and postprocessing.
 * This class only reads mission progress and cancels missions belonging to a playlist job.
 */
class GigaMissionController(private val context: Context) {
    private var manager: DownloadManager? = null
    private var connection: ServiceConnection? = null

    val isConnected: Boolean
        get() = manager != null

    suspend fun connect(): Boolean {
        if (manager != null) {
            return true
        }
        return suspendCancellableCoroutine { continuation ->
            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val binder = service as? DownloadManagerBinder
                    manager = binder?.downloadManager
                    if (continuation.isActive) {
                        continuation.resume(manager != null)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    manager = null
                }
            }
            connection = serviceConnection
            val intent = Intent(context, DownloadManagerService::class.java)
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound && continuation.isActive) {
                continuation.resume(false)
            }
            continuation.invokeOnCancellation {
                disconnect()
            }
        }
    }

    fun disconnect() {
        val serviceConnection = connection ?: return
        connection = null
        manager = null
        runCatching { context.unbindService(serviceConnection) }
    }

    fun missionProgress(): Map<String, MissionProgress> {
        val downloadManager = manager ?: return emptyMap()
        val result = mutableMapOf<String, MissionProgress>()
        val iterator = downloadManager.iterator
        try {
            for (index in 0 until iterator.oldListSize) {
                val mission = iterator.getItem(index).mission ?: continue
                val uri = runCatching { mission.storage?.uri?.toString() }.getOrNull()
                    ?: continue
                val done = if (mission is DownloadMission) mission.done else mission.length
                val total = if (mission is DownloadMission) mission.length else mission.length
                result[uri] = MissionProgress(done, total)
            }
        } finally {
            iterator.end()
        }
        return result
    }

    fun cancelMissions(targetUris: Set<String>) {
        val downloadManager = manager ?: return
        if (targetUris.isEmpty()) {
            return
        }
        val iterator = downloadManager.iterator
        try {
            for (index in 0 until iterator.oldListSize) {
                val mission = iterator.getItem(index).mission ?: continue
                val uri = runCatching { mission.storage?.uri?.toString() }.getOrNull()
                    ?: continue
                if (uri in targetUris && mission is DownloadMission) {
                    downloadManager.deleteMission(mission, true)
                }
            }
        } finally {
            iterator.end()
        }
    }

    data class MissionProgress(val downloadedBytes: Long, val totalBytes: Long)
}
