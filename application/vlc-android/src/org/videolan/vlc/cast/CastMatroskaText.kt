/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * Reads Matroska text subtitles by scanning EBML headers and subtitle blocks.
 * Does not decode or copy video/audio samples. Only seekable, local movies are supported.
 */
internal object CastMatroskaText {
    data class Track(val number: Int, val language: String, val format: String)
    private data class Cue(val startMs: Long, val endMs: Long?, val content: String)
    private data class Element(val id: Long, val data: Long, val end: Long)

    private const val EBML = 0x1A45DFA3L
    private const val SEGMENT = 0x18538067L
    private const val INFO = 0x1549A966L
    private const val TRACKS = 0x1654AE6BL
    private const val TRACK_ENTRY = 0xAEL
    private const val TRACK_NUMBER = 0xD7L
    private const val TRACK_TYPE = 0x83L
    private const val CODEC_ID = 0x86L
    private const val LANGUAGE = 0x22B59CL
    private const val LANGUAGE_IETF = 0x22B59DL
    private const val TIMECODE_SCALE = 0x2AD7B1L
    private const val CLUSTER = 0x1F43B675L
    private const val CLUSTER_TIMESTAMP = 0xE7L
    private const val SIMPLE_BLOCK = 0xA3L
    private const val BLOCK_GROUP = 0xA0L
    private const val BLOCK = 0xA1L
    private const val BLOCK_DURATION = 0x9BL
    private const val MAX_CUE_BYTES = 512 * 1024
    private const val MAX_DOCUMENT_CHARS = 5 * 1024 * 1024

    private class Reader(private val channel: FileChannel) {
        val length: Long = channel.size()
        fun bytes(at: Long, count: Int): ByteArray {
            require(count in 0..MAX_CUE_BYTES && at >= 0 && at <= length - count) { "Invalid Matroska read" }
            val buffer = ByteBuffer.allocate(count)
            var offset = at
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, offset)
                require(n > 0) { "Unexpected end of Matroska file" }
                offset += n
            }
            return buffer.array()
        }
        fun element(at: Long, parentEnd: Long): Element? {
            if (at >= parentEnd) return null
            val header = bytes(at, minOf(12L, parentEnd - at).toInt())
            val first = header[0].toInt() and 0xff
            require(first != 0) { "Invalid EBML element" }
            var idLength = 1
            while (idLength <= 4 && first and (0x80 shr (idLength - 1)) == 0) idLength++
            require(idLength <= 4 && header.size >= idLength + 1) { "Invalid EBML identifier" }
            var id = 0L
            for (i in 0 until idLength) id = (id shl 8) or (header[i].toLong() and 0xff)
            val firstSize = header[idLength].toInt() and 0xff
            require(firstSize != 0) { "Invalid EBML element size" }
            var sizeLength = 1
            while (sizeLength <= 8 && firstSize and (0x80 shr (sizeLength - 1)) == 0) sizeLength++
            require(sizeLength <= 8 && header.size >= idLength + sizeLength) { "Truncated EBML size" }
            var size = (firstSize and ((1 shl (8 - sizeLength)) - 1)).toLong()
            for (i in 1 until sizeLength) size = (size shl 8) or (header[idLength + i].toLong() and 0xff)
            val data = at + idLength + sizeLength
            val unknown = size == (1L shl (7 * sizeLength)) - 1
            val end = if (unknown) parentEnd else data + size
            require(end >= data && end <= parentEnd && end <= length) { "Invalid Matroska element extent" }
            return Element(id, data, end)
        }
        fun number(element: Element): Long {
            val size = element.end - element.data
            require(size in 1..8) { "Invalid EBML integer" }
            var value = 0L
            for (byte in bytes(element.data, size.toInt())) value = (value shl 8) or (byte.toLong() and 0xff)
            return value
        }
        fun string(element: Element): String {
            val n = element.end - element.data
            require(n in 0..4096) { "Invalid text field" }
            return String(bytes(element.data, n.toInt()), Charsets.UTF_8).trimEnd('\u0000')
        }
    }

    private fun <T> withReader(context: Context, uri: Uri, action: (Reader) -> T): T {
        val local = CastMovieLocation.localFile(context, uri) ?: uri
        if (local.scheme == "file" || local.scheme == null) {
            return FileInputStream(File(requireNotNull(local.path))).channel.use { action(Reader(it)) }
        }
        require(local.scheme == "content") { "Only local video files are supported" }
        val descriptor = context.contentResolver.openFileDescriptor(local, "r")
            ?: error("The movie cannot be opened for embedded subtitle extraction")
        return descriptor.use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                action(Reader(channel))
            }
        }
    }

    private data class Metadata(val segment: Element, val scaleNs: Long, val tracks: List<Track>)
    private fun metadata(reader: Reader): Metadata {
        var pos = 0L
        var segment: Element? = null
        while (pos < reader.length) {
            val element = reader.element(pos, reader.length) ?: break
            if (element.id == SEGMENT) { segment = element; break }
            if (element.id != EBML) error("Not a Matroska file")
            pos = element.end
        }
        val container = segment ?: error("Not a Matroska file")
        var scale = 1_000_000L
        val tracks = mutableListOf<Track>()
        pos = container.data
        while (pos < container.end) {
            val element = reader.element(pos, container.end) ?: break
            if (element.id == INFO) {
                var at = element.data
                while (at < element.end) {
                    val field = reader.element(at, element.end) ?: break
                    if (field.id == TIMECODE_SCALE) scale = reader.number(field)
                    at = field.end
                }
            } else if (element.id == TRACKS) {
                var at = element.data
                while (at < element.end) {
                    val entry = reader.element(at, element.end) ?: break
                    if (entry.id == TRACK_ENTRY) {
                        var index = -1
                        var type = -1
                        var codec = ""
                        var language = "und"
                        var fieldPos = entry.data
                        while (fieldPos < entry.end) {
                            val field = reader.element(fieldPos, entry.end) ?: break
                            when (field.id) {
                                TRACK_NUMBER -> index = reader.number(field).toInt()
                                TRACK_TYPE -> type = reader.number(field).toInt()
                                CODEC_ID -> codec = reader.string(field)
                                LANGUAGE, LANGUAGE_IETF -> language = reader.string(field)
                            }
                            fieldPos = field.end
                        }
                        val format = when (codec) {
                            "S_TEXT/UTF8", "S_TEXT/ASCII" -> "SRT"
                            "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA" -> "ASS/SSA"
                            "S_TEXT/WEBVTT" -> "WebVTT"
                            else -> null
                        }
                        if (type == 17 && index > 0 && format != null) tracks += Track(index, language, format)
                    }
                    at = entry.end
                }
            }
            pos = element.end
        }
        require(scale in 1L..1_000_000_000L) { "Unsupported Matroska timestamp scale" }
        return Metadata(container, scale, tracks)
    }

    fun tracks(context: Context, uri: Uri): List<Track> =
        withReader(context, uri) { metadata(it).tracks }

    private fun decodeCue(raw: String, format: String): String {
        var text = raw.trimEnd('\u0000').trim()
        if (format == "ASS/SSA") {
            val fields = text.removePrefix("Dialogue:").split(',', limit = 9)
            if (fields.size == 9) text = fields[8]
            text = text.replace(Regex("""\{[^}]*\}"""), "")
                .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        }
        return text.trim()
    }

    private fun readBlock(reader: Reader, element: Element, track: Track, clusterTime: Long,
                          scaleNs: Long, duration: Long?, cues: MutableList<Cue>) {
        val bytes = reader.bytes(element.data, minOf(12L, element.end - element.data).toInt())
        if (bytes.size < 4) return
        val first = bytes[0].toInt() and 0xff
        var length = 1
        while (length <= 8 && first and (0x80 shr (length - 1)) == 0) length++
        if (length > 8 || bytes.size < length + 3) return
        var number = (first and ((1 shl (8 - length)) - 1)).toLong()
        for (i in 1 until length) number = (number shl 8) or (bytes[i].toLong() and 0xff)
        if (number != track.number.toLong()) return
        val flags = bytes[length + 2].toInt() and 0xff
        if (flags and 0x06 != 0) return // Laced subtitle events not supported.
        val relative = ((bytes[length + 0].toInt() and 0xff) shl 8 or
            (bytes[length + 1].toInt() and 0xff)).toShort().toInt()
        val start = ((clusterTime + relative).coerceAtLeast(0) * scaleNs / 1_000_000L)
        val count = element.end - element.data - length - 3
        require(count in 0..MAX_CUE_BYTES.toLong()) { "Embedded subtitle cue too large" }
        val bytesText = reader.bytes(element.data + length + 3, count.toInt())
        val clean = decodeCue(String(bytesText, Charsets.UTF_8), track.format)
        if (clean.isBlank()) return
        val end = duration?.let { start + it * scaleNs / 1_000_000L }
        require(cues.size < 100_000) { "Too many embedded subtitle cues" }
        cues += Cue(start, end, clean)
    }

    private fun readGroup(reader: Reader, group: Element, track: Track,
                          clusterTime: Long, scaleNs: Long, cues: MutableList<Cue>) {
        var block: Element? = null
        var duration: Long? = null
        var pos = group.data
        while (pos < group.end) {
            val child = reader.element(pos, group.end) ?: break
            when (child.id) {
                BLOCK -> block = child
                BLOCK_DURATION -> duration = reader.number(child)
            }
            pos = child.end
        }
        block?.let { readBlock(reader, it, track, clusterTime, scaleNs, duration, cues) }
    }

    fun extract(context: Context, uri: Uri, trackNumber: Int): String = withReader(context, uri) { reader ->
        val meta = metadata(reader)
        val track = meta.tracks.firstOrNull { it.number == trackNumber }
            ?: error("This movie does not have the selected embedded text track")
        val cues = mutableListOf<Cue>()
        var pos = meta.segment.data
        while (pos < meta.segment.end) {
            val cluster = reader.element(pos, meta.segment.end) ?: break
            if (cluster.id == CLUSTER) {
                var timecode = 0L
                var at = cluster.data
                // Read cluster timestamp before examining any blocks.
                while (at < cluster.end) {
                    val entry = reader.element(at, cluster.end) ?: break
                    if (entry.id == CLUSTER_TIMESTAMP) timecode = reader.number(entry)
                    at = entry.end
                }
                at = cluster.data
                while (at < cluster.end) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Subtitle extraction cancelled")
                    val entry = reader.element(at, cluster.end) ?: break
                    when (entry.id) {
                        SIMPLE_BLOCK -> readBlock(reader, entry, track, timecode, meta.scaleNs, null, cues)
                        BLOCK_GROUP -> readGroup(reader, entry, track, timecode, meta.scaleNs, cues)
                    }
                    at = entry.end
                }
            }
            pos = cluster.end
        }
        require(cues.isNotEmpty()) { "No readable embedded subtitle cues were found" }
        val ordered = cues.sortedBy { it.startMs }
        var chars = 0
        buildString {
            append("WEBVTT\n\n")
            for (i in ordered.indices) {
                val cue = ordered[i]
                chars += cue.content.length
                require(chars <= MAX_DOCUMENT_CHARS) { "Embedded subtitle track too large" }
                val next = ordered.getOrNull(i + 1)?.startMs
                val end = cue.endMs?.takeIf { it > cue.startMs }
                    ?: minOf(cue.startMs + 5000, next?.takeIf { it > cue.startMs } ?: Long.MAX_VALUE)
                fun time(ms: Long) = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
                    ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
                append(time(cue.startMs)).append(" --> ").append(time(maxOf(end, cue.startMs + 1)))
                    .append('\n').append(cue.content).append("\n\n")
            }
        }
    }
}
