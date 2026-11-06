/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File

/** Scan sample metadata, never decode or write the movie, before replacing a working cast. */
internal object CastVideoValidation {
    private data class Video(val frames: Long, val span: Long, val mime: String?, val width: Int, val height: Int, val rotation: Int)
    private fun inspect(file: File, aac: Boolean, check: () -> Unit): Video {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            if (aac) require((0 until extractor.trackCount).any {
                val format = extractor.getTrackFormat(it)
                format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2
            }) { "Compatible stereo audio is missing" }
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            var frames = 0L; var first = Long.MAX_VALUE; var last = Long.MIN_VALUE
            while (extractor.sampleTime >= 0) {
                check(); val time = extractor.sampleTime
                first = minOf(first, time); last = maxOf(last, time); frames++
                extractor.advance()
            }
            require(frames > 0) { "No video frames were prepared" }
            val metadata = MediaMetadataRetriever()
            val rotation = try { metadata.setDataSource(file.path); metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 }
                finally { metadata.release() }
            return Video(frames, last - first, format.getString(MediaFormat.KEY_MIME),
                format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), rotation)
        } finally { extractor.release() }
    }
    fun matches(original: File, output: File, check: () -> Unit): Boolean {
        val expected = inspect(original, false, check)
        val actual = inspect(output, true, check)
        return expected.frames == actual.frames && kotlin.math.abs(expected.span - actual.span) <= 2000 &&
            expected.mime == actual.mime && expected.width == actual.width && expected.height == actual.height && expected.rotation == actual.rotation
    }
}
