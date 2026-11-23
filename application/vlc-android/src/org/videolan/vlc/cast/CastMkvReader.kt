/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.Inflater

/**
 * Bounded Matroska/WebM reader over positional reads. Never copies the movie: it reads element
 * headers through a small window and only loads the payloads of blocks the caller asks for.
 *
 * Handles what real-world files use and the old subtitle scanner did not: zlib and header-stripping
 * content compression (mkvmerge's defaults for many tracks), Xiph/EBML/fixed lacing, unknown-size
 * clusters, and the Cues index for O(cues) random access instead of a whole-file walk.
 * Android-independent so it is exercised by JVM tests against ffmpeg-made files.
 */
internal class CastMkvReader(private val source: Source) : Closeable {
    interface Source : Closeable {
        val length: Long
        /** Reads up to [len] bytes at absolute position [at]; returns the count, or -1 at end. */
        fun read(at: Long, dst: ByteArray, off: Int, len: Int): Int
    }

    class ChannelSource(private val channel: FileChannel, private val owner: Closeable? = null) : Source {
        override val length: Long = channel.size()
        override fun read(at: Long, dst: ByteArray, off: Int, len: Int): Int = channel.read(ByteBuffer.wrap(dst, off, len), at)
        override fun close() { try { channel.close() } finally { owner?.close() } }
    }

    data class Encoding(val order: Long, val scope: Long, val type: Long, val algorithm: Long, val settings: ByteArray?)
    data class Track(
        val number: Int, val type: Int, val codecId: String, val codecPrivate: ByteArray?,
        val language: String, val name: String, val isDefault: Boolean, val forced: Boolean,
        val defaultDurationNs: Long, val sampleRate: Double, val channels: Int, val bitDepth: Int,
        val width: Int, val height: Int, val encodings: List<Encoding>
    ) {
        val isVideo get() = type == 1
        val isAudio get() = type == 2
        val isSubtitle get() = type == 17
        val encrypted get() = encodings.any { it.type == 1L }
    }
    class Block(val track: Int, val timeNs: Long, val durationNs: Long?, val keyframe: Boolean,
                val frames: List<ByteArray>, val position: Long, val cluster: Long)
    data class CuePoint(val timeNs: Long, val track: Int, val cluster: Long, val relative: Long?)

    private data class Element(val id: Long, val data: Long, val end: Long, val unknownSize: Boolean)

    val length = source.length
    var scaleNs = 1_000_000L; private set
    var durationNs = 0L; private set
    var segmentData = 0L; private set
    var segmentEnd = 0L; private set
    var firstCluster = -1L; private set
    var tracks: List<Track> = emptyList(); private set
    private var cuesPosition = -1L
    private var cueCache: List<CuePoint>? = null

    // Small read-ahead window: block headers of interleaved audio/subtitle blocks are close together.
    private val window = ByteArray(WINDOW)
    private var windowStart = -1L
    private var windowEnd = -1L
    private val seekTargets = HashMap<Long, Long>()

    init {
        try { parseHeader() } catch (e: Exception) { close(); throw e }
    }

    override fun close() = source.close()

    // ---- low level -------------------------------------------------------------------------

    private fun fill(at: Long) {
        var n = 0
        val want = minOf(WINDOW.toLong(), length - at).toInt()
        while (n < want) {
            val r = source.read(at + n, window, n, want - n)
            if (r <= 0) break
            n += r
        }
        windowStart = at; windowEnd = at + n
    }

    private fun byteAt(at: Long): Int {
        if (at < windowStart || at >= windowEnd) fill(at)
        if (at >= windowEnd) throw IOException("Unexpected end of Matroska file")
        return window[(at - windowStart).toInt()].toInt() and 0xff
    }

    fun bytes(at: Long, count: Int): ByteArray {
        require(count >= 0 && at >= 0 && at + count <= length) { "Invalid Matroska read" }
        val out = ByteArray(count)
        if (count <= WINDOW / 4) {
            if (at < windowStart || at + count > windowEnd) fill(at)
            System.arraycopy(window, (at - windowStart).toInt(), out, 0, count)
            return out
        }
        var done = 0
        while (done < count) {
            val r = source.read(at + done, out, done, count - done)
            if (r <= 0) throw IOException("Unexpected end of Matroska file")
            done += r
        }
        return out
    }

    private fun element(at: Long, parentEnd: Long): Element? {
        if (at >= parentEnd || at >= length) return null
        val first = byteAt(at)
        if (first == 0) throw IOException("Invalid EBML element")
        var idLength = 1
        while (idLength <= 4 && first and (0x80 shr (idLength - 1)) == 0) idLength++
        if (idLength > 4) throw IOException("Invalid EBML identifier")
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or byteAt(at + i).toLong()
        val firstSize = byteAt(at + idLength)
        if (firstSize == 0) throw IOException("Invalid EBML element size")
        var sizeLength = 1
        while (sizeLength <= 8 && firstSize and (0x80 shr (sizeLength - 1)) == 0) sizeLength++
        var size = (firstSize and ((1 shl (8 - sizeLength)) - 1)).toLong()
        var allOnes = size == ((1L shl (8 - sizeLength)) - 1)
        for (i in 1 until sizeLength) {
            val b = byteAt(at + idLength + i)
            if (b != 0xff) allOnes = false
            size = (size shl 8) or b.toLong()
        }
        val data = at + idLength + sizeLength
        val end = if (allOnes) minOf(parentEnd, length) else data + size
        if (end < data || end > minOf(parentEnd, length)) throw IOException("Invalid Matroska element extent")
        return Element(id, data, end, allOnes)
    }

    private fun uint(e: Element): Long {
        val n = (e.end - e.data).toInt()
        if (n == 0) return 0
        if (n !in 1..8) throw IOException("Invalid EBML integer")
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or byteAt(e.data + i).toLong()
        return v
    }

    private fun float(e: Element): Double {
        val b = bytes(e.data, (e.end - e.data).toInt())
        return when (b.size) {
            4 -> ByteBuffer.wrap(b).float.toDouble()
            8 -> ByteBuffer.wrap(b).double
            else -> 0.0
        }
    }

    private fun string(e: Element): String {
        val n = e.end - e.data
        if (n !in 0..4096) throw IOException("Invalid text field")
        return String(bytes(e.data, n.toInt()), Charsets.UTF_8).trimEnd('\u0000')
    }

    private inline fun children(parent: Element, visit: (Element) -> Unit) {
        var at = parent.data
        while (at < parent.end) {
            val child = element(at, parent.end) ?: break
            visit(child)
            if (child.unknownSize) break
            at = child.end
        }
    }

    // ---- header ----------------------------------------------------------------------------

    private fun parseHeader() {
        var pos = 0L
        var segment: Element? = null
        while (pos < length) {
            val e = element(pos, length) ?: break
            if (e.id == SEGMENT) { segment = e; break }
            if (e.id != EBML && e.id != VOID) throw IOException("Not a Matroska file")
            pos = e.end
        }
        val seg = segment ?: throw IOException("Not a Matroska file")
        segmentData = seg.data; segmentEnd = seg.end
        var info: Element? = null
        var trackList: Element? = null
        pos = seg.data
        // Stop at the first Cluster: metadata precedes media in conforming files; the SeekHead points
        // at anything stored later (typically Cues), so the whole movie is never walked here.
        while (pos < seg.end) {
            val e = element(pos, seg.end) ?: break
            when (e.id) {
                SEEK_HEAD -> readSeekHead(e)
                INFO -> info = e
                TRACKS -> trackList = e
                CUES -> cuesPosition = pos
                CLUSTER -> { firstCluster = pos }
            }
            if (e.id == CLUSTER || e.unknownSize) break
            pos = e.end
        }
        info?.let { readInfo(it) }
        if (trackList == null) {
            // Tracks stored after the first cluster: follow the SeekHead entry recorded earlier.
            trackList = seekTargets[TRACKS]?.let { element(it, seg.end) }
        }
        tracks = trackList?.let { readTracks(it) } ?: throw IOException("Matroska file has no track list")
        if (scaleNs !in 1L..1_000_000_000L) throw IOException("Unsupported Matroska timestamp scale")
        if (firstCluster < 0) firstCluster = seekTargets[CLUSTER] ?: -1L
        if (cuesPosition < 0) cuesPosition = seekTargets[CUES] ?: -1L
    }

    private fun readSeekHead(head: Element) {
        children(head) { seek ->
            if (seek.id != SEEK) return@children
            var id = -1L; var position = -1L
            children(seek) { f ->
                when (f.id) {
                    SEEK_ID -> { id = 0; for (b in bytes(f.data, (f.end - f.data).toInt().coerceAtMost(4))) id = (id shl 8) or (b.toLong() and 0xff) }
                    SEEK_POSITION -> position = uint(f)
                }
            }
            if (id > 0 && position >= 0 && segmentData + position < length) seekTargets.putIfAbsent(id, segmentData + position)
        }
    }

    private fun readInfo(info: Element) {
        var duration = 0.0
        children(info) { f ->
            when (f.id) {
                TIMESTAMP_SCALE -> scaleNs = uint(f)
                DURATION -> duration = float(f)
            }
        }
        durationNs = (duration * scaleNs).toLong()
    }

    private fun readTracks(list: Element): List<Track> {
        val result = ArrayList<Track>()
        children(list) { entry ->
            if (entry.id != TRACK_ENTRY) return@children
            var number = 0; var type = 0; var codec = ""; var private: ByteArray? = null
            var language = "eng"; var ietf: String? = null; var name = ""
            var isDefault = true; var forced = false; var defaultDuration = 0L
            var rate = 8000.0; var outputRate = 0.0; var channels = 1; var depth = 0; var width = 0; var height = 0
            val encodings = ArrayList<Encoding>()
            children(entry) { f ->
                when (f.id) {
                    TRACK_NUMBER -> number = uint(f).toInt()
                    TRACK_TYPE -> type = uint(f).toInt()
                    CODEC_ID -> codec = string(f)
                    CODEC_PRIVATE -> {
                        val n = f.end - f.data
                        if (n > MAX_PRIVATE) throw IOException("Codec configuration is too large")
                        private = bytes(f.data, n.toInt())
                    }
                    LANGUAGE -> language = string(f)
                    LANGUAGE_IETF -> ietf = string(f)
                    NAME -> name = string(f)
                    FLAG_DEFAULT -> isDefault = uint(f) != 0L
                    FLAG_FORCED -> forced = uint(f) != 0L
                    DEFAULT_DURATION -> defaultDuration = uint(f)
                    VIDEO -> children(f) { v -> when (v.id) { PIXEL_WIDTH -> width = uint(v).toInt(); PIXEL_HEIGHT -> height = uint(v).toInt() } }
                    AUDIO -> children(f) { a ->
                        when (a.id) {
                            SAMPLING_FREQUENCY -> rate = float(a)
                            OUTPUT_SAMPLING_FREQUENCY -> outputRate = float(a)
                            CHANNELS -> channels = uint(a).toInt()
                            BIT_DEPTH -> depth = uint(a).toInt()
                        }
                    }
                    CONTENT_ENCODINGS -> children(f) { enc ->
                        if (enc.id != CONTENT_ENCODING) return@children
                        var order = 0L; var scope = 1L; var encType = 0L; var algorithm = 0L; var settings: ByteArray? = null
                        children(enc) { c ->
                            when (c.id) {
                                CONTENT_ENCODING_ORDER -> order = uint(c)
                                CONTENT_ENCODING_SCOPE -> scope = uint(c)
                                CONTENT_ENCODING_TYPE -> encType = uint(c)
                                CONTENT_COMPRESSION -> children(c) { z ->
                                    when (z.id) {
                                        CONTENT_COMP_ALGO -> algorithm = uint(z)
                                        CONTENT_COMP_SETTINGS -> settings = bytes(z.data, (z.end - z.data).toInt().coerceAtMost(4096))
                                    }
                                }
                                CONTENT_ENCRYPTION -> encType = 1
                            }
                        }
                        encodings += Encoding(order, scope, encType, algorithm, settings)
                    }
                }
            }
            if (number > 0) result += Track(number, type, codec, private, ietf?.takeIf { it.isNotBlank() } ?: language, name,
                isDefault, forced, defaultDuration, if (outputRate > 0) outputRate else rate, channels, depth, width, height,
                encodings.sortedByDescending { it.order })
        }
        return result
    }

    // ---- cues ------------------------------------------------------------------------------

    /** Index entries; empty when the file has no Cues. Loaded once, lazily. */
    fun cues(): List<CuePoint> {
        cueCache?.let { return it }
        val list = ArrayList<CuePoint>()
        if (cuesPosition < 0) findTrailingCues()
        val cues = if (cuesPosition >= 0) element(cuesPosition, segmentEnd)?.takeIf { it.id == CUES } else null
        if (cues != null) children(cues) { point ->
            if (point.id != CUE_POINT) return@children
            var time = -1L
            val positions = ArrayList<Triple<Int, Long, Long?>>()
            children(point) { f ->
                when (f.id) {
                    CUE_TIME -> time = uint(f)
                    CUE_TRACK_POSITIONS -> {
                        var track = 0; var cluster = -1L; var relative: Long? = null
                        children(f) { p ->
                            when (p.id) {
                                CUE_TRACK -> track = uint(p).toInt()
                                CUE_CLUSTER_POSITION -> cluster = uint(p)
                                CUE_RELATIVE_POSITION -> relative = uint(p)
                            }
                        }
                        if (track > 0 && cluster >= 0) positions += Triple(track, cluster, relative)
                    }
                }
            }
            if (time >= 0) for ((track, cluster, relative) in positions)
                list += CuePoint(time * scaleNs, track, segmentData + cluster, relative)
        }
        list.sortBy { it.timeNs }
        cueCache = list
        return list
    }

    /** No SeekHead entry: hop over top-level elements (cluster sizes are known) to find Cues. */
    private fun findTrailingCues() {
        var pos = if (firstCluster >= 0) firstCluster else return
        var hops = 0
        while (pos < segmentEnd && hops++ < MAX_TOP_LEVEL_HOPS) {
            val e = element(pos, segmentEnd) ?: return
            if (e.id == CUES) { cuesPosition = pos; return }
            if (e.unknownSize) return
            pos = e.end
        }
    }

    // ---- blocks ----------------------------------------------------------------------------

    /**
     * Visits blocks of [wanted] tracks in file (decode) order starting at the cluster at [from].
     * Blocks of other tracks are skipped after reading only their header. Return false to stop.
     */
    fun blocks(from: Long, wanted: Set<Int>, payload: Boolean = true, visit: (Block) -> Boolean) {
        val byNumber = tracks.associateBy { it.number }
        var pos = from
        while (pos in 0 until segmentEnd) {
            val cluster = element(pos, segmentEnd) ?: return
            if (cluster.id != CLUSTER) {
                if (cluster.unknownSize) return
                pos = cluster.end; continue
            }
            var clusterTime = -1L
            var at = cluster.data
            var next = cluster.end
            val deferred = ArrayList<Element>()
            while (at < cluster.end) {
                val e = element(at, cluster.end) ?: break
                // An unknown-size cluster ends where the next top-level element begins.
                if (cluster.unknownSize && e.id in TOP_LEVEL) { next = at; break }
                when (e.id) {
                    CLUSTER_TIMESTAMP -> {
                        clusterTime = uint(e)
                        for (d in deferred) if (!handle(d, clusterTime, wanted, byNumber, payload, pos, visit)) return
                        deferred.clear()
                    }
                    SIMPLE_BLOCK, BLOCK_GROUP ->
                        if (clusterTime < 0) deferred += e
                        else if (!handle(e, clusterTime, wanted, byNumber, payload, pos, visit)) return
                }
                if (e.unknownSize) { next = e.end; break }
                at = e.end
            }
            if (cluster.unknownSize && next == cluster.end) next = at
            if (next <= pos) return
            pos = next
        }
    }

    /** Timestamp of the cluster at [position], from its first Timestamp child. */
    fun clusterTime(position: Long): Long? {
        val cluster = element(position, segmentEnd)?.takeIf { it.id == CLUSTER } ?: return null
        children(cluster) { e -> if (e.id == CLUSTER_TIMESTAMP) return uint(e) }
        return null
    }

    /** Reads the single block element at [position] inside a cluster whose timestamp is [clusterTime]. */
    fun blockAt(position: Long, clusterTime: Long, wanted: Set<Int>): Block? {
        val e = element(position, segmentEnd) ?: return null
        if (e.id != SIMPLE_BLOCK && e.id != BLOCK_GROUP) return null
        var found: Block? = null
        handle(e, clusterTime, wanted, tracks.associateBy { it.number }, true, -1) { found = it; false }
        return found
    }

    private fun handle(e: Element, clusterTime: Long, wanted: Set<Int>, byNumber: Map<Int, Track>,
                       payload: Boolean, cluster: Long, visit: (Block) -> Boolean): Boolean {
        var blockElement = e
        var duration: Long? = null
        var keyframe: Boolean
        if (e.id == BLOCK_GROUP) {
            var block: Element? = null
            var referenced = false
            children(e) { c ->
                when (c.id) {
                    BLOCK -> block = c
                    BLOCK_DURATION -> duration = uint(c)
                    REFERENCE_BLOCK -> referenced = true
                }
            }
            blockElement = block ?: return true
            keyframe = !referenced
        } else keyframe = false
        val start = blockElement.data
        val first = byteAt(start)
        var n = 1
        while (n <= 8 && first and (0x80 shr (n - 1)) == 0) n++
        if (n > 4) return true
        var number = (first and ((1 shl (8 - n)) - 1)).toLong()
        for (i in 1 until n) number = (number shl 8) or byteAt(start + i).toLong()
        if (number.toInt() !in wanted) return true
        val track = byNumber[number.toInt()] ?: return true
        val relative = ((byteAt(start + n) shl 8) or byteAt(start + n + 1)).toShort().toInt()
        val flags = byteAt(start + n + 2)
        if (e.id == SIMPLE_BLOCK) keyframe = flags and 0x80 != 0
        val headerEnd = start + n + 3
        val size = blockElement.end - headerEnd
        if (size < 0 || size > MAX_BLOCK) throw IOException("Matroska block is too large")
        val frames = if (payload) unlace(bytes(headerEnd, size.toInt()), flags and 0x06).map { decode(track, it) } else emptyList()
        val time = (clusterTime + relative) * scaleNs
        return visit(Block(track.number, time, duration?.let { it * scaleNs }, keyframe, frames, e.data, cluster))
    }

    private fun unlace(data: ByteArray, lacing: Int): List<ByteArray> {
        if (lacing == 0) return listOf(data)
        if (data.isEmpty()) throw IOException("Invalid laced block")
        val count = (data[0].toInt() and 0xff) + 1
        var pos = 1
        val sizes = LongArray(count)
        when (lacing) {
            0x02 -> { // Xiph
                for (i in 0 until count - 1) {
                    var size = 0L
                    while (true) { val b = data[pos++].toInt() and 0xff; size += b; if (b != 255) break }
                    sizes[i] = size
                }
            }
            0x06 -> { // EBML
                fun vint(signed: Boolean): Long {
                    val f = data[pos].toInt() and 0xff
                    var len = 1
                    while (len <= 8 && f and (0x80 shr (len - 1)) == 0) len++
                    var v = (f and ((1 shl (8 - len)) - 1)).toLong()
                    for (i in 1 until len) v = (v shl 8) or (data[pos + i].toLong() and 0xff)
                    pos += len
                    return if (signed) v - ((1L shl (7 * len - 1)) - 1) else v
                }
                sizes[0] = vint(false)
                for (i in 1 until count - 1) sizes[i] = sizes[i - 1] + vint(true)
            }
            0x04 -> { // fixed
                val each = (data.size - 1) / count
                for (i in 0 until count - 1) sizes[i] = each.toLong()
            }
        }
        var used = 0L
        for (i in 0 until count - 1) used += sizes[i]
        sizes[count - 1] = data.size - pos - used
        if (sizes.any { it < 0 }) throw IOException("Invalid laced block")
        return List(count) { i -> val s = sizes[i].toInt(); data.copyOfRange(pos, pos + s).also { pos += s } }
    }

    private fun decode(track: Track, frame: ByteArray): ByteArray {
        var data = frame
        for (enc in track.encodings) {
            if (enc.scope and 1L == 0L) continue
            if (enc.type != 0L) throw IOException("Encrypted tracks are not supported")
            data = when (enc.algorithm) {
                0L -> inflate(data)
                3L -> (enc.settings ?: ByteArray(0)) + data
                else -> throw IOException("Unsupported Matroska compression")
            }
        }
        return data
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 3)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BLOCK) throw IOException("Compressed block is too large")
            }
            return out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            throw IOException("Corrupt compressed Matroska block", e)
        } finally { inflater.end() }
    }

    companion object {
        private const val WINDOW = 64 * 1024
        private const val MAX_PRIVATE = 1024 * 1024
        private const val MAX_BLOCK = 64L * 1024 * 1024
        private const val MAX_TOP_LEVEL_HOPS = 200_000
        const val EBML = 0x1A45DFA3L
        const val SEGMENT = 0x18538067L
        const val SEEK_HEAD = 0x114D9B74L
        const val SEEK = 0x4DBBL
        const val SEEK_ID = 0x53ABL
        const val SEEK_POSITION = 0x53ACL
        const val INFO = 0x1549A966L
        const val TIMESTAMP_SCALE = 0x2AD7B1L
        const val DURATION = 0x4489L
        const val TRACKS = 0x1654AE6BL
        const val TRACK_ENTRY = 0xAEL
        const val TRACK_NUMBER = 0xD7L
        const val TRACK_TYPE = 0x83L
        const val CODEC_ID = 0x86L
        const val CODEC_PRIVATE = 0x63A2L
        const val LANGUAGE = 0x22B59CL
        const val LANGUAGE_IETF = 0x22B59DL
        const val NAME = 0x536EL
        const val FLAG_DEFAULT = 0x88L
        const val FLAG_FORCED = 0x55AAL
        const val DEFAULT_DURATION = 0x23E383L
        const val VIDEO = 0xE0L
        const val PIXEL_WIDTH = 0xB0L
        const val PIXEL_HEIGHT = 0xBAL
        const val AUDIO = 0xE1L
        const val SAMPLING_FREQUENCY = 0xB5L
        const val OUTPUT_SAMPLING_FREQUENCY = 0x78B5L
        const val CHANNELS = 0x9FL
        const val BIT_DEPTH = 0x6264L
        const val CONTENT_ENCODINGS = 0x6D80L
        const val CONTENT_ENCODING = 0x6240L
        const val CONTENT_ENCODING_ORDER = 0x5031L
        const val CONTENT_ENCODING_SCOPE = 0x5032L
        const val CONTENT_ENCODING_TYPE = 0x5033L
        const val CONTENT_COMPRESSION = 0x5034L
        const val CONTENT_COMP_ALGO = 0x4254L
        const val CONTENT_COMP_SETTINGS = 0x4255L
        const val CONTENT_ENCRYPTION = 0x5035L
        const val CLUSTER = 0x1F43B675L
        const val CLUSTER_TIMESTAMP = 0xE7L
        const val SIMPLE_BLOCK = 0xA3L
        const val BLOCK_GROUP = 0xA0L
        const val BLOCK = 0xA1L
        const val BLOCK_DURATION = 0x9BL
        const val REFERENCE_BLOCK = 0xFBL
        const val CUES = 0x1C53BB6BL
        const val CUE_POINT = 0xBBL
        const val CUE_TIME = 0xB3L
        const val CUE_TRACK_POSITIONS = 0xB7L
        const val CUE_TRACK = 0xF7L
        const val CUE_CLUSTER_POSITION = 0xF1L
        const val CUE_RELATIVE_POSITION = 0xF0L
        const val VOID = 0xECL
        private val TOP_LEVEL = setOf(CLUSTER, CUES, SEEK_HEAD, INFO, TRACKS, 0x1043A770L, 0x1941A469L, 0x1254C367L)
    }
}
