/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

enum class PlaylistCacheJobState {
    QUEUED,
    RUNNING,
    COMPLETE,
    PARTIAL,
    CANCELED,
    FAILED
}

enum class PlaylistCacheTrackState {
    PENDING,
    RESOLVING,
    SCHEDULED,
    DOWNLOADING,
    COMPLETE,
    FAILED,
    CANCELED,
    SKIPPED
}

/**
 * A playlist-level cache request.
 *
 * [jobKey] identifies the playlist independently of the database row id, for example
 * `remote:1:https://...` or `local:42`.
 */
@Entity(
    tableName = PlaylistCacheJobEntity.TABLE_NAME,
    indices = [Index(value = [PlaylistCacheJobEntity.JOB_KEY], unique = true)]
)
data class PlaylistCacheJobEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = ID)
    val id: Long = 0,

    @ColumnInfo(name = JOB_KEY)
    val jobKey: String,

    @ColumnInfo(name = SERVICE_ID)
    val serviceId: Int,

    @ColumnInfo(name = PLAYLIST_URL)
    val playlistUrl: String,

    @ColumnInfo(name = IS_LOCAL)
    val isLocal: Boolean,

    @ColumnInfo(name = LOCAL_PLAYLIST_ID)
    val localPlaylistId: Long?,

    @ColumnInfo(name = PLAYLIST_NAME)
    val playlistName: String,

    @ColumnInfo(name = THUMBNAIL_URL)
    val thumbnailUrl: String?,

    @ColumnInfo(name = STATE)
    var state: String = PlaylistCacheJobState.QUEUED.name,

    @ColumnInfo(name = TOTAL_TRACKS)
    var totalTracks: Int = 0,

    @ColumnInfo(name = COMPLETED_TRACKS)
    var completedTracks: Int = 0,

    @ColumnInfo(name = FAILED_TRACKS)
    var failedTracks: Int = 0,

    @ColumnInfo(name = CREATED_AT)
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = UPDATED_AT)
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TABLE_NAME = "cache_jobs"
        const val ID = "id"
        const val JOB_KEY = "job_key"
        const val SERVICE_ID = "service_id"
        const val PLAYLIST_URL = "playlist_url"
        const val IS_LOCAL = "is_local"
        const val LOCAL_PLAYLIST_ID = "local_playlist_id"
        const val PLAYLIST_NAME = "playlist_name"
        const val THUMBNAIL_URL = "thumbnail_url"
        const val STATE = "state"
        const val TOTAL_TRACKS = "total_tracks"
        const val COMPLETED_TRACKS = "completed_tracks"
        const val FAILED_TRACKS = "failed_tracks"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }
}

/**
 * One ordered track inside a playlist cache job.
 *
 * [cacheKey] is the playback cache key, and it links this playlist reference to the shared
 * [CachedAudioEntity]. Multiple playlists may reference the same [cacheKey].
 */
@Entity(
    tableName = PlaylistCacheTrackEntity.TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = PlaylistCacheJobEntity::class,
            parentColumns = [PlaylistCacheJobEntity.ID],
            childColumns = [PlaylistCacheTrackEntity.JOB_ID],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = [PlaylistCacheTrackEntity.JOB_ID]),
        Index(value = [PlaylistCacheTrackEntity.CACHE_KEY]),
        Index(value = [PlaylistCacheTrackEntity.TARGET_URI]),
        Index(value = [PlaylistCacheTrackEntity.SERVICE_ID, PlaylistCacheTrackEntity.STREAM_URL])
    ]
)
data class PlaylistCacheTrackEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = ID)
    val id: Long = 0,

    @ColumnInfo(name = JOB_ID)
    val jobId: Long,

    @ColumnInfo(name = POSITION)
    val position: Int,

    @ColumnInfo(name = SERVICE_ID)
    val serviceId: Int,

    @ColumnInfo(name = STREAM_URL)
    val streamUrl: String,

    @ColumnInfo(name = STREAM_INFO_ID)
    val streamInfoId: String? = null,

    @ColumnInfo(name = TITLE)
    val title: String,

    @ColumnInfo(name = UPLOADER)
    val uploader: String,

    @ColumnInfo(name = UPLOADER_URL)
    val uploaderUrl: String? = null,

    @ColumnInfo(name = DURATION_SECONDS)
    val durationSeconds: Long,

    @ColumnInfo(name = THUMBNAIL_URL)
    val thumbnailUrl: String? = null,

    @ColumnInfo(name = STREAM_TYPE)
    val streamType: String,

    @ColumnInfo(name = CACHE_KEY)
    var cacheKey: String? = null,

    @ColumnInfo(name = AUDIO_FORMAT)
    var audioFormat: String? = null,

    @ColumnInfo(name = AUDIO_BITRATE)
    var audioBitrate: Int = 0,

    @ColumnInfo(name = AUDIO_TRACK_ID)
    var audioTrackId: String? = null,

    @ColumnInfo(name = STATE)
    var state: String = PlaylistCacheTrackState.PENDING.name,

    @ColumnInfo(name = TARGET_URI)
    var targetUri: String? = null,

    @ColumnInfo(name = DOWNLOADED_BYTES)
    var downloadedBytes: Long = 0,

    @ColumnInfo(name = TOTAL_BYTES)
    var totalBytes: Long = 0,

    @ColumnInfo(name = ERROR)
    var error: String? = null,

    @ColumnInfo(name = UPDATED_AT)
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TABLE_NAME = "cache_tracks"
        const val ID = "id"
        const val JOB_ID = "job_id"
        const val POSITION = "position"
        const val SERVICE_ID = "service_id"
        const val STREAM_URL = "stream_url"
        const val STREAM_INFO_ID = "stream_info_id"
        const val TITLE = "title"
        const val UPLOADER = "uploader"
        const val UPLOADER_URL = "uploader_url"
        const val DURATION_SECONDS = "duration_seconds"
        const val THUMBNAIL_URL = "thumbnail_url"
        const val STREAM_TYPE = "stream_type"
        const val CACHE_KEY = "cache_key"
        const val AUDIO_FORMAT = "audio_format"
        const val AUDIO_BITRATE = "audio_bitrate"
        const val AUDIO_TRACK_ID = "audio_track_id"
        const val STATE = "state"
        const val TARGET_URI = "target_uri"
        const val DOWNLOADED_BYTES = "downloaded_bytes"
        const val TOTAL_BYTES = "total_bytes"
        const val ERROR = "error"
        const val UPDATED_AT = "updated_at"
    }
}

/**
 * A physical cached audio file shared by one or more playlist cache track references.
 */
@Entity(
    tableName = CachedAudioEntity.TABLE_NAME,
    indices = [Index(value = [CachedAudioEntity.CACHE_KEY], unique = true)]
)
data class CachedAudioEntity(
    @PrimaryKey
    @ColumnInfo(name = CACHE_KEY)
    val cacheKey: String,

    @ColumnInfo(name = FILE_PATH)
    val filePath: String,

    @ColumnInfo(name = METADATA_PATH)
    val metadataPath: String?,

    @ColumnInfo(name = FORMAT)
    val format: String?,

    @ColumnInfo(name = BITRATE)
    val bitrate: Int,

    @ColumnInfo(name = AUDIO_TRACK_ID)
    val audioTrackId: String?,

    @ColumnInfo(name = SIZE_BYTES)
    val sizeBytes: Long,

    @ColumnInfo(name = METADATA_VERSION)
    val metadataVersion: Int,

    @ColumnInfo(name = CREATED_AT)
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = UPDATED_AT)
    var updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val TABLE_NAME = "cached_audio"
        const val CACHE_KEY = "cache_key"
        const val FILE_PATH = "file_path"
        const val METADATA_PATH = "metadata_path"
        const val FORMAT = "format"
        const val BITRATE = "bitrate"
        const val AUDIO_TRACK_ID = "audio_track_id"
        const val SIZE_BYTES = "size_bytes"
        const val METADATA_VERSION = "metadata_version"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"
    }
}
