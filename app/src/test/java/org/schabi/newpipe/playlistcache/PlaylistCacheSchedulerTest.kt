/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlaylistCacheSchedulerTest {
    @Test
    fun remoteJobKeyIsStableAndDistinct() {
        val first = PlaylistCacheScheduler.remoteJobKey(1, "https://example.com/playlist")
        val second = PlaylistCacheScheduler.remoteJobKey(1, "https://example.com/playlist")
        val other = PlaylistCacheScheduler.remoteJobKey(2, "https://example.com/playlist")

        assertEquals(first, second)
        assertNotEquals(first, other)
    }

    @Test
    fun localAndRemoteJobKeysDoNotCollide() {
        val local = PlaylistCacheScheduler.localJobKey(42)
        val remote = PlaylistCacheScheduler.remoteJobKey(42, "42")

        assertNotEquals(local, remote)
    }

    @Test
    fun workNameIsStablePerJob() {
        val jobKey = PlaylistCacheScheduler.localJobKey(7)
        val first = PlaylistCacheScheduler.workName(jobKey)
        val second = PlaylistCacheScheduler.workName(jobKey)

        assertEquals(first, second)
        assertEquals("playlist_cache_work_", first.take("playlist_cache_work_".length))
    }
}
