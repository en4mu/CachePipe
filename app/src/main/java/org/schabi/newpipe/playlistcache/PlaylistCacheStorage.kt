/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.playlistcache

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.security.MessageDigest
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * App-private persistent storage for playlist-cached audio and its offline metadata.
 *
 * Giga writes to a `.part` target created here. The final file is only produced after the
 * download completes, so an interrupted download is never visible to playback.
 */
class PlaylistCacheStorage(context: Context) {
    private val appContext = context.applicationContext

    val rootDir: File = (
        appContext.getExternalFilesDir(CACHE_DIR_NAME)
            ?: File(appContext.filesDir, CACHE_DIR_NAME)
        ).also { it.mkdirs() }

    private val audioDir: File = File(rootDir, AUDIO_DIR_NAME).also { it.mkdirs() }
    private val metadataDir: File = File(rootDir, METADATA_DIR_NAME).also { it.mkdirs() }

    fun partFile(cacheKey: String, suffix: String?): File {
        return File(audioDir, "${hashOf(cacheKey)}.${normalizeSuffix(suffix)}.part")
    }

    fun finalFile(cacheKey: String, suffix: String?): File {
        return File(audioDir, "${hashOf(cacheKey)}.${normalizeSuffix(suffix)}")
    }

    fun metadataFile(cacheKey: String): File {
        return File(metadataDir, "${hashOf(cacheKey)}.ser")
    }

    /**
     * Creates an empty target file for Giga. Giga validates that the storage file exists before
     * starting a mission.
     */
    @Throws(IOException::class)
    fun createTargetFile(file: File): File {
        file.parentFile?.mkdirs()
        if (file.exists() && !file.delete()) {
            throw IOException("Could not replace cache target ${file.absolutePath}")
        }
        if (!file.createNewFile()) {
            throw IOException("Could not create cache target ${file.absolutePath}")
        }
        return file
    }

    /**
     * Atomically promotes a completed `.part` file to its final path.
     */
    fun finalizeDownload(part: File, final: File): Boolean {
        final.parentFile?.mkdirs()
        if (final.exists() && !final.delete()) {
            return false
        }
        return part.renameTo(final)
    }

    fun writeStreamInfo(cacheKey: String, info: StreamInfo): File? {
        val target = metadataFile(cacheKey)
        val temp = File(target.parentFile, "${target.name}.tmp")
        return try {
            ObjectOutputStream(FileOutputStream(temp)).use { output ->
                output.writeInt(METADATA_VERSION)
                output.writeObject(info)
            }
            if (target.exists() && !target.delete()) {
                return null
            }
            if (!temp.renameTo(target)) {
                null
            } else {
                target
            }
        } catch (e: IOException) {
            temp.delete()
            null
        }
    }

    fun readStreamInfo(cacheKey: String): StreamInfo? {
        val file = metadataFile(cacheKey)
        if (!isUsable(file)) {
            return null
        }
        return try {
            ObjectInputStream(FileInputStream(file)).use { input ->
                val version = input.readInt()
                if (version != METADATA_VERSION) {
                    null
                } else {
                    input.readObject() as? StreamInfo
                }
            }
        } catch (e: IOException) {
            null
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: ClassCastException) {
            null
        }
    }

    fun isUsable(file: File?): Boolean {
        return file != null && file.isFile && file.length() > 0 && file.canRead()
    }

    fun deleteQuietly(file: File?) {
        file?.delete()
    }

    fun listAudioFiles(): List<File> {
        return audioDir.listFiles()?.toList().orEmpty()
    }

    fun listMetadataFiles(): List<File> {
        return metadataDir.listFiles()?.toList().orEmpty()
    }

    fun deleteAllFiles(): Boolean {
        var success = true
        listAudioFiles().forEach { if (!it.delete()) success = false }
        listMetadataFiles().forEach { if (!it.delete()) success = false }
        return success
    }

    private fun hashOf(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return buildString(digest.size * 2) {
            for (byte in digest) {
                append("%02x".format(byte.toInt() and 0xff))
            }
        }
    }

    private fun normalizeSuffix(suffix: String?): String {
        val sanitized = suffix?.lowercase()?.filter { it.isLetterOrDigit() }
        return if (sanitized.isNullOrEmpty()) DEFAULT_SUFFIX else sanitized
    }

    companion object {
        const val METADATA_VERSION = 1

        private const val CACHE_DIR_NAME = "playlist_cache"
        private const val AUDIO_DIR_NAME = "audio"
        private const val METADATA_DIR_NAME = "metadata"
        private const val DEFAULT_SUFFIX = "audio"
    }
}
