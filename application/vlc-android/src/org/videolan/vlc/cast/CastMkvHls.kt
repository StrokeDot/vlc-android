/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import java.io.OutputStream
import java.util.Locale
import kotlin.math.abs

/**
 * Plans and writes an HLS (CMAF) rendition of a Matroska movie without converting it first.
 *
 * Video is copied sample-for-sample into keyframe-aligned fMP4 segments that are produced only
 * when the receiver asks for them, so playback starts after one segment instead of after a full
 * rewrite of the movie (the reason the old converter took minutes). Audio uses a fixed 1024-sample
 * AAC grid shared by every segment, so copied, transcoded and silent-filled segments always join.
 * Android-independent; tested on the JVM against ffmpeg/mkvmerge files.
 */
internal class CastMkvHls(private val reader: CastMkvReader, val video: CastMkvReader.Track, val audio: CastMkvReader.Track?) {
    class Segment(val startNs: Long, val endNs: Long, val cluster: Long) {
        val durationSeconds get() = (endNs - startNs) / 1e9
    }

    val segments: List<Segment>
    val videoConfig: CastFmp4.Video
    val videoCodec: String
    /** Source audio is AAC-LC mono/stereo and can be copied without decoding. */
    val audioCopy: Boolean
    /** Output audio format: the copied source format, or 48 kHz/44.1 kHz stereo AAC-LC when transcoding. */
    var audioConfig: CastFmp4.Audio? = null; private set
    private var audioAnchorSamples = 0L
    private val frameNs: Long

    init {
        require(!video.encrypted) { "Encrypted video cannot be cast" }
        val private = requireNotNull(video.codecPrivate) { "The video has no codec configuration" }
        videoConfig = when (video.codecId) {
            "V_MPEG4/ISO/AVC" -> CastFmp4.Video("avc1", "avcC", private, video.width, video.height)
            "V_MPEGH/ISO/HEVC" -> CastFmp4.Video("hvc1", "hvcC", private, video.width, video.height)
            else -> throw IllegalArgumentException("This video codec needs full conversion")
        }
        videoCodec = if (videoConfig.fourcc == "avc1") CastFmp4.avcCodec(private) else CastFmp4.hevcCodec(private)
        frameNs = video.defaultDurationNs.takeIf { it in 1_000_000L..200_000_000L } ?: 41_708_333L
        segments = plan()
        require(segments.isNotEmpty()) { "No keyframes found in this video" }
        audioCopy = audio != null && isCopyableAac(audio)
        if (audio != null && audioCopy) {
            val rate = audio.sampleRate.toInt()
            val asc = audio.codecPrivate?.takeIf { it.size >= 2 } ?: CastFmp4.aacConfig(rate, audio.channels)
            audioConfig = CastFmp4.Audio(asc, audio.channels, rate)
            audioAnchorSamples = firstAudioTimeNs()?.let { it * rate / 1_000_000_000L } ?: 0L
        }
    }

    /** Grid frame where the source audio begins (sequential transcoders label their output from here). */
    fun transcodedStartFrame(): Long {
        val rate = requireNotNull(audioConfig).sampleRate.toLong()
        val first = firstAudioTimeNs() ?: 0L
        return (first * rate / 1_000_000_000L + AAC_FRAME / 2) / AAC_FRAME
    }

    /** Called by the transcoder once it knows its output rate; the grid is anchored at time zero. */
    fun useTranscodedAudio(sampleRate: Int) {
        audioConfig = CastFmp4.Audio(CastFmp4.aacConfig(sampleRate, 2), 2, sampleRate)
        audioAnchorSamples = 0
    }

    // ---- planning --------------------------------------------------------------------------

    private class Key(val timeNs: Long, val cluster: Long)

    private fun plan(): List<Segment> {
        var keys = reader.cues().filter { it.track == video.number }.map { Key(it.timeNs, it.cluster) }
        val duration = reader.durationNs
        // Muxers that index too sparsely (or not at all) get one header-only pass over the clusters.
        if (keys.size < 2 || duration > 0 && keys.zipWithNext().maxOf { it.second.timeNs - it.first.timeNs } > MAX_SEGMENT_NS) {
            val scanned = ArrayList<Key>()
            reader.blocks(reader.firstCluster, setOf(video.number), payload = false) { b ->
                if (b.keyframe && (scanned.isEmpty() || b.timeNs > scanned.last().timeNs)) scanned += Key(b.timeNs, b.cluster)
                true
            }
            keys = scanned
        }
        keys = keys.sortedBy { it.timeNs }.distinctBy { it.timeNs }
        if (keys.isEmpty()) return emptyList()
        val end = (if (duration > 0) duration else lastVideoTimeNs(keys.last()) + frameNs).coerceAtLeast(keys.last().timeNs + frameNs)
        val starts = ArrayList<Key>()
        for (k in keys) if (starts.isEmpty() || k.timeNs - starts.last().timeNs >= TARGET_NS) starts += k
        // Avoid a tiny last segment.
        if (starts.size > 1 && end - starts.last().timeNs < TARGET_NS / 3) starts.removeAt(starts.lastIndex)
        return starts.mapIndexed { i, k -> Segment(k.timeNs, starts.getOrNull(i + 1)?.timeNs ?: end, k.cluster) }
    }

    private fun lastVideoTimeNs(from: Key): Long {
        var last = from.timeNs
        reader.blocks(from.cluster, setOf(video.number), payload = false) { b -> if (b.timeNs > last) last = b.timeNs; true }
        return last
    }

    private fun firstAudioTimeNs(): Long? {
        var first: Long? = null
        val a = audio ?: return null
        reader.blocks(reader.firstCluster, setOf(a.number), payload = false) { b -> first = b.timeNs; false }
        return first
    }

    private fun isCopyableAac(track: CastMkvReader.Track): Boolean {
        if (!track.codecId.startsWith("A_AAC") || track.channels !in 1..2 || track.encrypted) return false
        val asc = track.codecPrivate
        val objectType = if (asc != null && asc.size >= 2) (asc[0].toInt() and 0xff) ushr 3
            else if (track.codecId.endsWith("/LC")) 2 else 0
        return objectType == 2 && track.sampleRate.toInt() in intArrayOf(48000, 44100, 32000, 24000, 22050, 16000)
    }

    // ---- playlists -------------------------------------------------------------------------

    fun masterPlaylist(fileBytes: Long): String {
        val seconds = (segments.last().endNs - segments.first().startNs) / 1e9
        val bandwidth = ((fileBytes * 8 / seconds.coerceAtLeast(1.0)) * 1.3).toLong().coerceAtLeast(500_000L)
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        val codecs = if (audio != null) "$videoCodec,mp4a.40.2" else videoCodec
        if (audio != null) sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"")
            .append(if (audioCopy) audio.channels else 2).append("\",URI=\"audio.m3u8\"\n")
        sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(bandwidth).append(",CODECS=\"").append(codecs).append('"')
        if (videoConfig.width > 0 && videoConfig.height > 0) sb.append(",RESOLUTION=").append(videoConfig.width).append('x').append(videoConfig.height)
        if (audio != null) sb.append(",AUDIO=\"aac\"")
        sb.append("\nvideo.m3u8\n")
        return sb.toString()
    }

    fun mediaPlaylist(audioRendition: Boolean): String {
        val target = segments.maxOf { kotlin.math.ceil(it.durationSeconds).toInt() }.coerceAtLeast(1)
        val prefix = if (audioRendition) "a" else "v"
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:").append(target)
            .append("\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-INDEPENDENT-SEGMENTS\n#EXT-X-MAP:URI=\"")
            .append(if (audioRendition) "audio-init.mp4" else "video-init.mp4").append("\"\n")
        segments.forEachIndexed { i, s ->
            sb.append("#EXTINF:").append(String.format(Locale.ROOT, "%.6f", s.durationSeconds)).append(",\n")
                .append(prefix).append(i).append(".m4s\n")
        }
        return sb.append("#EXT-X-ENDLIST\n").toString()
    }

    /** Segment index for a presentation time, for transcoders deciding where to (re)start. */
    fun segmentAt(timeNs: Long): Int = segments.indexOfLast { it.startNs <= timeNs }.coerceAtLeast(0)

    // ---- video -----------------------------------------------------------------------------

    fun writeVideo(index: Int, out: OutputStream) {
        val segment = segments[index]
        val end = segments.getOrNull(index + 1)?.startNs ?: Long.MAX_VALUE
        val pts = ArrayList<Long>()
        val data = ArrayList<ByteArray>()
        val keys = ArrayList<Boolean>()
        var started = false
        reader.blocks(segment.cluster, setOf(video.number)) { b ->
            if (!started) {
                if (b.timeNs > segment.startNs + MAX_SEGMENT_NS) return@blocks false
                if (!(b.keyframe && abs(b.timeNs - segment.startNs) < 1_000_000L)) return@blocks true
                started = true
            } else if (b.keyframe && b.timeNs >= end) return@blocks false
            for ((i, frame) in b.frames.withIndex()) {
                pts += toVideoUnits(b.timeNs + i * frameNs); data += frame; keys += b.keyframe && i == 0
            }
            true
        }
        require(started && data.isNotEmpty()) { "Video segment $index is missing its keyframe" }
        // Matroska stores presentation times in decode order. Decode times are the sorted
        // presentation times; reordering then shows up as (possibly negative) composition offsets.
        val dts = pts.sorted()
        val frameUnits = toVideoUnits(frameNs).toInt().coerceAtLeast(1)
        val nextDts = segments.getOrNull(index + 1)?.let { toVideoUnits(it.startNs) }
        val samples = data.indices.map { i ->
            val duration = if (i + 1 < dts.size) dts[i + 1] - dts[i]
                else nextDts?.let { (it - dts[i]).takeIf { d -> d in 1..frameUnits * 4L } } ?: frameUnits.toLong()
            CastFmp4.Sample(data[i], duration.toInt().coerceAtLeast(1), (pts[i] - dts[i]).toInt(), keys[i])
        }
        CastFmp4.fragment(out, index + 1, dts[0], samples)
    }

    private fun toVideoUnits(ns: Long) = ns * 9 / 100_000

    // ---- audio -----------------------------------------------------------------------------

    /** AAC frame numbers (on the shared grid) that belong to segment [index]. */
    fun audioFrames(index: Int): LongRange {
        val rate = requireNotNull(audioConfig).sampleRate.toLong()
        fun frameAt(ns: Long): Long {
            val samples = ns * rate / 1_000_000_000L - audioAnchorSamples
            return Math.floorDiv(samples + AAC_FRAME - 1, AAC_FRAME.toLong())
        }
        val first = frameAt(segments[index].startNs)
        val last = if (index + 1 < segments.size) frameAt(segments[index + 1].startNs) else frameAt(segments[index].endNs)
        return first until maxOf(last, first + 1)
    }

    /** Sample time (track timescale) of grid frame [n]. */
    fun audioFrameTime(n: Long) = audioAnchorSamples + n * AAC_FRAME

    /** Writes audio segment [index]; frames the source does not cover become silence. */
    fun writeAudio(index: Int, out: OutputStream, frame: (Long) -> ByteArray?) {
        val config = requireNotNull(audioConfig)
        val range = audioFrames(index)
        val silent = if (config.channels == 1) SILENT_MONO else SILENT_STEREO
        val samples = range.map { CastFmp4.Sample(frame(it) ?: silent, AAC_FRAME, 0, true) }
        CastFmp4.fragment(out, index + 1, audioFrameTime(range.first).coerceAtLeast(0), samples)
    }

    /** Copies source AAC frames for segment [index] onto the grid. */
    fun writeAudioCopy(index: Int, out: OutputStream) {
        val track = requireNotNull(audio)
        val config = requireNotNull(audioConfig)
        val range = audioFrames(index)
        val frames = HashMap<Long, ByteArray>()
        val aacNs = AAC_FRAME * 1_000_000_000L / config.sampleRate
        // Audio may be interleaved slightly before the video keyframe that starts the cluster.
        val from = segments[(index - 1).coerceAtLeast(0)].cluster
        val stopNs = segments[index].endNs + 2_000_000_000L
        reader.blocks(from, setOf(track.number)) { b ->
            for ((i, f) in b.frames.withIndex()) {
                val ns = b.timeNs + i * aacNs
                val n = Math.floorDiv(ns * config.sampleRate / 1_000_000_000L - audioAnchorSamples + AAC_FRAME / 2, AAC_FRAME.toLong())
                if (n in range) frames.putIfAbsent(n, f)
            }
            b.timeNs < stopNs
        }
        writeAudio(index, out) { frames[it] }
    }

    companion object {
        const val AAC_FRAME = 1024
        private const val TARGET_NS = 6_000_000_000L
        private const val MAX_SEGMENT_NS = 30_000_000_000L
        /** Standard silent AAC-LC raw frames (no ADTS header). */
        val SILENT_STEREO = byteArrayOf(0x21, 0x00, 0x49, 0x90.toByte(), 0x02, 0x19, 0x00, 0x23, 0x80.toByte())
        val SILENT_MONO = byteArrayOf(0x01, 0x40, 0x20, 0x07)

        fun pickVideo(reader: CastMkvReader) = reader.tracks.firstOrNull {
            it.isVideo && !it.encrypted && it.codecId in setOf("V_MPEG4/ISO/AVC", "V_MPEGH/ISO/HEVC") && it.codecPrivate != null
        }

        fun pickAudio(reader: CastMkvReader) = reader.tracks.filter { it.isAudio && !it.encrypted }
            .let { all -> all.firstOrNull { it.isDefault } ?: all.firstOrNull() }
    }
}
