/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/** Compare every sample timestamp without decoding or rewriting the movie. */
internal object CastVideoValidation {
    private data class Header(val mime: String?, val width: Int, val height: Int, val rotation: Int)
    private fun selectVideo(file: File, extractor: MediaExtractor, aac: Boolean): Header {
        extractor.setDataSource(file.path)
        if (aac) require((0 until extractor.trackCount).any {
            val format = extractor.getTrackFormat(it)
            format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2
        }) { "Compatible stereo audio is missing" }
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val format = extractor.getTrackFormat(track)
        extractor.selectTrack(track)
        val metadata = MediaMetadataRetriever()
        val rotation = try { metadata.setDataSource(file.path); metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 }
            finally { metadata.release() }
        return Header(format.getString(MediaFormat.KEY_MIME), format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT), rotation)
    }
    fun matches(original: File, output: File, check: () -> Unit): Boolean {
        val expected = MediaExtractor(); val actual = MediaExtractor()
        try {
            val expectedHeader = selectVideo(original, expected, false); val actualHeader = selectVideo(output, actual, true)
            if (expectedHeader != actualHeader) { Log.w("VLCPlus/CastPerf", "validation=header expected=$expectedHeader actual=$actualHeader"); return false }
            val expectedStart = expected.sampleTime; val actualStart = actual.sampleTime
            if (expectedStart < 0 || actualStart < 0) return false
            var count = 0L
            while (expected.sampleTime >= 0 && actual.sampleTime >= 0) {
                check()
                if (kotlin.math.abs((expected.sampleTime - expectedStart) - (actual.sampleTime - actualStart)) > 2000L) {
                    Log.w("VLCPlus/CastPerf", "validation=timestamp frame=$count expected=${expected.sampleTime - expectedStart} actual=${actual.sampleTime - actualStart}"); return false
                }
                count++
                expected.advance(); actual.advance()
            }
            val complete = expected.sampleTime < 0 && actual.sampleTime < 0
            if (!complete) Log.w("VLCPlus/CastPerf", "validation=frame-count matched=$count originalEof=${expected.sampleTime < 0} outputEof=${actual.sampleTime < 0}")
            return complete
        } finally { expected.release(); actual.release() }
    }
}
