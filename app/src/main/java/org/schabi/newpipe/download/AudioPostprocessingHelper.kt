/*
 * SPDX-FileCopyrightText: 2026 CachePipe contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.download

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.Stream
import us.shandian.giga.postprocessing.Postprocessing

/**
 * Shared selection of the post-processing algorithm for audio-only downloads.
 *
 * Manual downloads and playlist cache downloads must produce the same playable container.
 */
object AudioPostprocessingHelper {
    @JvmStatic
    fun postprocessingName(stream: Stream): String? {
        return when (stream.format) {
            MediaFormat.M4A -> Postprocessing.ALGORITHM_M4A_NO_DASH
            MediaFormat.WEBMA_OPUS -> Postprocessing.ALGORITHM_OGG_FROM_WEBM_DEMUXER
            else -> null
        }
    }

    /**
     * Suffix of the file produced after post-processing.
     */
    @JvmStatic
    fun outputSuffix(stream: Stream): String? {
        return if (stream.format == MediaFormat.WEBMA_OPUS) {
            "opus"
        } else {
            stream.format?.suffix
        }
    }
}
