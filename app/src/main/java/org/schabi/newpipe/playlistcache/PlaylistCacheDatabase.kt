/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.concurrent.Volatile

@Database(
    entities = [
        PlaylistCacheJobEntity::class,
        PlaylistCacheTrackEntity::class,
        CachedAudioEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class PlaylistCacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): PlaylistCacheDao

    companion object {
        const val DATABASE_NAME = "playlist_cache.db"
        const val DATABASE_VERSION = 1

        @Volatile
        private var instance: PlaylistCacheDatabase? = null

        @JvmStatic
        fun getInstance(context: Context): PlaylistCacheDatabase {
            var result = instance
            if (result == null) {
                synchronized(PlaylistCacheDatabase::class.java) {
                    result = instance
                    if (result == null) {
                        result = Room.databaseBuilder(
                            context.applicationContext,
                            PlaylistCacheDatabase::class.java,
                            DATABASE_NAME
                        ).setDriver(BundledSQLiteDriver()).build()
                        instance = result
                    }
                }
            }
            return checkNotNull(result)
        }

        @JvmStatic
        fun closeInstance() {
            synchronized(PlaylistCacheDatabase::class.java) {
                instance?.close()
                instance = null
            }
        }
    }
}
