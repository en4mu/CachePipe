/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistCacheDao {
    // region Jobs

    @Query("SELECT * FROM ${PlaylistCacheJobEntity.TABLE_NAME} WHERE ${PlaylistCacheJobEntity.JOB_KEY} = :jobKey LIMIT 1")
    suspend fun getJobByKey(jobKey: String): PlaylistCacheJobEntity?

    @Query("SELECT * FROM ${PlaylistCacheJobEntity.TABLE_NAME} WHERE ${PlaylistCacheJobEntity.ID} = :jobId LIMIT 1")
    suspend fun getJobById(jobId: Long): PlaylistCacheJobEntity?

    @Query("SELECT * FROM ${PlaylistCacheJobEntity.TABLE_NAME} WHERE ${PlaylistCacheJobEntity.JOB_KEY} = :jobKey LIMIT 1")
    fun observeJobByKey(jobKey: String): Flow<PlaylistCacheJobEntity?>

    @Query("SELECT * FROM ${PlaylistCacheJobEntity.TABLE_NAME} ORDER BY ${PlaylistCacheJobEntity.UPDATED_AT} DESC")
    fun observeJobs(): Flow<List<PlaylistCacheJobEntity>>

    @Query("SELECT * FROM ${PlaylistCacheJobEntity.TABLE_NAME} ORDER BY ${PlaylistCacheJobEntity.UPDATED_AT} DESC")
    suspend fun getJobs(): List<PlaylistCacheJobEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: PlaylistCacheJobEntity): Long

    @Update
    suspend fun updateJob(job: PlaylistCacheJobEntity)

    @Query("DELETE FROM ${PlaylistCacheJobEntity.TABLE_NAME} WHERE ${PlaylistCacheJobEntity.ID} = :jobId")
    suspend fun deleteJob(jobId: Long)

    // endregion

    // region Tracks

    @Query("SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.JOB_ID} = :jobId ORDER BY ${PlaylistCacheTrackEntity.POSITION}")
    suspend fun getTracksForJob(jobId: Long): List<PlaylistCacheTrackEntity>

    @Query("SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.JOB_ID} = :jobId ORDER BY ${PlaylistCacheTrackEntity.POSITION}")
    fun observeTracksForJob(jobId: Long): Flow<List<PlaylistCacheTrackEntity>>

    @Query("SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.JOB_ID} = :jobId AND ${PlaylistCacheTrackEntity.STATE} = :state ORDER BY ${PlaylistCacheTrackEntity.POSITION}")
    suspend fun getTracksForJobByState(
        jobId: Long,
        state: String,
    ): List<PlaylistCacheTrackEntity>

    @Query("SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.JOB_ID} = :jobId AND ${PlaylistCacheTrackEntity.STATE} IN (:states) ORDER BY ${PlaylistCacheTrackEntity.POSITION}")
    suspend fun getTracksForJobByStates(
        jobId: Long,
        states: List<String>,
    ): List<PlaylistCacheTrackEntity>

    @Query("SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.JOB_ID} = :jobId AND ${PlaylistCacheTrackEntity.STATE} = :state ORDER BY ${PlaylistCacheTrackEntity.POSITION}")
    fun observeTracksForJobByState(
        jobId: Long,
        state: String,
    ): Flow<List<PlaylistCacheTrackEntity>>

    @Query("SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.ID} = :trackId LIMIT 1")
    suspend fun getTrackById(trackId: Long): PlaylistCacheTrackEntity?

    @Query("SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.TARGET_URI} = :targetUri LIMIT 1")
    suspend fun getTrackByTargetUri(targetUri: String): PlaylistCacheTrackEntity?

    @Query(
        "SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} " +
            "WHERE ${PlaylistCacheTrackEntity.SERVICE_ID} = :serviceId " +
            "AND ${PlaylistCacheTrackEntity.STREAM_URL} = :streamUrl " +
            "AND ${PlaylistCacheTrackEntity.STATE} = :state " +
            "ORDER BY ${PlaylistCacheTrackEntity.UPDATED_AT} DESC LIMIT 1",
    )
    suspend fun getTrackForStreamByState(
        serviceId: Int,
        streamUrl: String,
        state: String,
    ): PlaylistCacheTrackEntity?

    @Query(
        "SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} " +
            "WHERE ${PlaylistCacheTrackEntity.SERVICE_ID} = :serviceId " +
            "AND ${PlaylistCacheTrackEntity.STREAM_URL} = :streamUrl " +
            "AND ${PlaylistCacheTrackEntity.STATE} IN (:states) " +
            "ORDER BY ${PlaylistCacheTrackEntity.UPDATED_AT} DESC LIMIT 1",
    )
    suspend fun getTrackForStreamByStates(
        serviceId: Int,
        streamUrl: String,
        states: List<String>,
    ): PlaylistCacheTrackEntity?

    @Insert
    suspend fun insertTracks(tracks: List<PlaylistCacheTrackEntity>): List<Long>

    @Update
    suspend fun updateTrack(track: PlaylistCacheTrackEntity)

    @Query("DELETE FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.JOB_ID} = :jobId")
    suspend fun deleteTracksForJob(jobId: Long)

    @Query("SELECT * FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.CACHE_KEY} = :cacheKey")
    suspend fun getTracksByCacheKey(cacheKey: String): List<PlaylistCacheTrackEntity>

    // endregion

    // region Shared cached audio

    @Query("SELECT * FROM ${CachedAudioEntity.TABLE_NAME} WHERE ${CachedAudioEntity.CACHE_KEY} = :cacheKey LIMIT 1")
    suspend fun getCachedAudio(cacheKey: String): CachedAudioEntity?

    @Query("SELECT * FROM ${CachedAudioEntity.TABLE_NAME} ORDER BY ${CachedAudioEntity.UPDATED_AT} DESC")
    suspend fun getAllCachedAudio(): List<CachedAudioEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedAudio(audio: CachedAudioEntity)

    @Query("DELETE FROM ${CachedAudioEntity.TABLE_NAME} WHERE ${CachedAudioEntity.CACHE_KEY} = :cacheKey")
    suspend fun deleteCachedAudio(cacheKey: String)

    @Query("SELECT COUNT(*) FROM ${PlaylistCacheTrackEntity.TABLE_NAME} WHERE ${PlaylistCacheTrackEntity.CACHE_KEY} = :cacheKey")
    suspend fun countCacheReferences(cacheKey: String): Int

    // endregion
}
