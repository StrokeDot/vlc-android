/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.Locale

/**
 * Reads embedded text subtitle samples without rewriting the movie.
 * Image-based subtitle tracks are not supported by Chromecast text tracks.
 */
internal object CastEmbeddedSubtitles {
    data class Track(val index: Int, val language: String, val format: String, val name: String = "", val forced: Boolean = false) {
        val label: String get() = buildString {
            append(language.ifBlank { "Unknown language" }).append(" — ").append(format)
            if (name.isNotBlank()) append(" — ").append(name)
            if (forced) append(" (forced)")
        }
    }
    private data class Cue(val start: Long, val end: Long?, val text: String)
    // Re-selecting a track (or reconnecting) must not rescan a multi-gigabyte movie.
    private val cache = object : LinkedHashMap<String, String>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 3
    }
    private val supported = setOf(
        "application/x-subrip", "text/x-subrip", "text/srt",
        "text/x-ssa", "text/x-ass", "application/x-ass",
        "text/vtt", "text/webvtt", "text/3gpp", "application/x-quicktime-tx3g"
    )
    private val timing = Regex("""(?m)^\s*(\d{1,3}:\d{2}:\d{2}[,.]\d{3})\s*-->\s*(\d{1,3}:\d{2}:\d{2}[,.]\d{3}).*$""")
    private val numbered = Regex("""(?m)^\s*\d+\s*$""")

    private fun extractor(context: Context, movie: Uri): MediaExtractor = MediaExtractor().also {
        try { it.setDataSource(context, movie, null) }
        catch (e: Exception) {
            it.release()
            throw IllegalArgumentException("Android cannot inspect embedded subtitles in this movie", e)
        }
    }
    private fun mime(format: MediaFormat) = format.getString(MediaFormat.KEY_MIME)?.lowercase(Locale.ROOT).orEmpty()
    private fun name(type: String) = when (type) {
        "application/x-subrip", "text/x-subrip", "text/srt" -> "SRT"
        "text/x-ssa", "text/x-ass", "application/x-ass" -> "ASS/SSA"
        "text/3gpp", "application/x-quicktime-tx3g" -> "Timed text"
        else -> "WebVTT"
    }
    fun tracks(context: Context, movie: Uri): List<Track> {
        // For Matroska use original EBML block durations; device MediaExtractor
        // often omits MKV text tracks entirely.
        val matroska = runCatching { CastMatroskaText.tracks(context, movie) }.getOrNull()
        if (!matroska.isNullOrEmpty()) {
            return matroska.map { Track(-it.number, it.language, it.format, it.name, it.forced) }
        }
        val demux = extractor(context, movie)
        return try {
            (0 until demux.trackCount).mapNotNull { index ->
                val format = demux.getTrackFormat(index)
                val type = mime(format)
                if (type !in supported) null
                else Track(index, format.getString(MediaFormat.KEY_LANGUAGE) ?: "und", name(type))
            }
        } finally { demux.release() }
    }

    /** Full WebVTT document, ready for the existing local Cast HTTP server. */
    fun extract(context: Context, movie: Uri, track: Int): String {
        val key = "$movie#$track#" + runCatching { CastMovieLocation.localFile(context, movie)?.path?.let { java.io.File(it) }
            ?.let { "${it.length()}:${it.lastModified()}" } }.getOrNull()
        synchronized(cache) { cache[key] }?.let { return it }
        return extractUncached(context, movie, track).also { synchronized(cache) { cache[key] = it } }
    }

    private fun extractUncached(context: Context, movie: Uri, track: Int): String {
        if (track < 0) return CastMatroskaText.extract(context, movie, -track)
        val demux = extractor(context, movie)
        try {
            require(track in 0 until demux.trackCount) { "That subtitle track no longer exists" }
            val type = mime(demux.getTrackFormat(track))
            require(type in supported) { "This embedded subtitle format is not supported" }
            demux.selectTrack(track)
            val cues = ArrayList<Cue>()
            val buffer = ByteBuffer.allocate(512 * 1024)
            var total = 0
            while (demux.sampleTrackIndex >= 0) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Subtitle extraction stopped")
                buffer.clear()
                val size = demux.readSampleData(buffer, 0)
                if (size < 0) break
                require(size <= buffer.capacity()) { "An embedded subtitle cue is too large" }
                if (size > 0) {
                    val bytes = ByteArray(size)
                    buffer.position(0); buffer.get(bytes)
                    val text = decode(bytes, type)
                    val match = timing.find(text)
                    val cueStart = (demux.sampleTime / 1000).coerceAtLeast(0)
                    val embeddedStart = match?.groupValues?.get(1)?.let(::millis)
                    val embeddedEnd = match?.groupValues?.get(2)?.let(::millis)
                    val end = if (embeddedStart != null && embeddedEnd != null && embeddedEnd > embeddedStart)
                        cueStart + (embeddedEnd - embeddedStart) else null
                    val cleaned = clean(text, type)
                    if (cleaned.isNotBlank()) {
                        total += cleaned.length
                        require(total <= 5 * 1024 * 1024 && cues.size < 100_000) { "Embedded subtitles are too large" }
                        cues += Cue(cueStart, end, cleaned)
                    }
                }
                if (!demux.advance()) break
            }
            require(cues.isNotEmpty()) { "No readable text captions were found in this track" }
            return CastCueText.render(cues.map { CastCueText.Cue(it.start, it.end, it.text) })
        } finally { demux.release() }
    }

    private fun decode(bytes: ByteArray, type: String): String {
        var offset = 0
        var count = bytes.size
        // MP4 tx3g samples contain a two-byte big-endian text length before UTF text.
        if (type == "text/3gpp" || type == "application/x-quicktime-tx3g") {
            if (count < 2) return ""
            val length = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
            if (length > count - 2) return ""
            offset = 2; count = length
        }
        val charset = if (count >= 2 && bytes[offset] == 0xfe.toByte() && bytes[offset + 1] == 0xff.toByte())
            Charset.forName("UTF-16BE")
        else if (count >= 2 && bytes[offset] == 0xff.toByte() && bytes[offset + 1] == 0xfe.toByte())
            Charset.forName("UTF-16LE")
        else Charsets.UTF_8
        return String(bytes, offset, count, charset).trimStart('\uFEFF')
    }

    private fun clean(sample: String, type: String): String =
        if (type in setOf("text/x-ssa", "text/x-ass", "application/x-ass")) CastCueText.fromAss(sample, matroska = false)
        else CastCueText.fromSrt(sample.replace(timing, "").replace(numbered, "").trim().removePrefix("WEBVTT"))

    private fun millis(stamp: String): Long? {
        val parts = stamp.replace(',', '.').split(':', '.')
        if (parts.size != 4) return null
        return try {
            (parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()) * 1000 + parts[3].toLong()
        } catch (_: NumberFormatException) { null }
    }
}
