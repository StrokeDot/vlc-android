/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.OutputStream

/**
 * Minimal CMAF / fragmented-MP4 writer for HLS: one track per file, an init segment (ftyp+moov)
 * and media segments (moof+mdat). Samples are written untouched, so H.264/HEVC frames copied from
 * Matroska (length-prefixed NAL units, same avcC/hvcC configuration) need no rewriting at all.
 */
internal object CastFmp4 {
    sealed class Config(val timescale: Int)
    class Video(val fourcc: String, val configBox: String, val config: ByteArray, val width: Int, val height: Int) : Config(90_000)
    class Audio(val audioSpecificConfig: ByteArray, val channels: Int, val sampleRate: Int) : Config(sampleRate)

    class Sample(val data: ByteArray, val duration: Int, val compositionOffset: Int, val keyframe: Boolean)

    private class Box(type: String) {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        init { out.writeInt(0); out.writeBytes(type) }
        fun full(version: Int, flags: Int) = apply { out.writeInt((version shl 24) or flags) }
        fun add(child: Box) = apply { out.write(child.done()) }
        fun done(): ByteArray {
            val b = bytes.toByteArray()
            val n = b.size
            b[0] = (n ushr 24).toByte(); b[1] = (n ushr 16).toByte(); b[2] = (n ushr 8).toByte(); b[3] = n.toByte()
            return b
        }
    }

    private fun box(type: String, build: DataOutputStream.() -> Unit = {}) = Box(type).apply { out.build() }
    private fun fullBox(type: String, version: Int, flags: Int, build: DataOutputStream.() -> Unit = {}) =
        Box(type).full(version, flags).apply { out.build() }

    private val matrix = intArrayOf(0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000)

    fun init(config: Config): ByteArray {
        val video = config as? Video
        val audio = config as? Audio
        val ftyp = box("ftyp") { writeBytes("iso6"); writeInt(0); writeBytes("iso6"); writeBytes("cmfc"); writeBytes("mp41"); writeBytes("isom") }
        val mvhd = fullBox("mvhd", 0, 0) {
            writeInt(0); writeInt(0); writeInt(1000); writeInt(0)
            writeInt(0x00010000); writeShort(0x0100); writeShort(0); writeInt(0); writeInt(0)
            matrix.forEach { writeInt(it) }
            repeat(6) { writeInt(0) }
            writeInt(2)
        }
        val tkhd = fullBox("tkhd", 0, 3) {
            writeInt(0); writeInt(0); writeInt(1); writeInt(0); writeInt(0)
            writeInt(0); writeInt(0)
            writeShort(0); writeShort(0); writeShort(if (audio != null) 0x0100 else 0); writeShort(0)
            matrix.forEach { writeInt(it) }
            writeInt((video?.width ?: 0) shl 16); writeInt((video?.height ?: 0) shl 16)
        }
        val mdhd = fullBox("mdhd", 0, 0) {
            writeInt(0); writeInt(0); writeInt(config.timescale); writeInt(0)
            writeShort(0x55c4); writeShort(0) // language "und"
        }
        val hdlr = fullBox("hdlr", 0, 0) {
            writeInt(0); writeBytes(if (video != null) "vide" else "soun"); writeInt(0); writeInt(0); writeInt(0)
            writeBytes(if (video != null) "VideoHandler" else "SoundHandler"); writeByte(0)
        }
        val header = if (video != null) fullBox("vmhd", 0, 1) { writeLong(0) } else fullBox("smhd", 0, 0) { writeInt(0) }
        val dinf = box("dinf").add(fullBox("dref", 0, 0) { writeInt(1) }.add(fullBox("url ", 0, 1)))
        val entry = if (video != null) {
            box(video.fourcc) {
                repeat(6) { writeByte(0) }; writeShort(1)
                writeShort(0); writeShort(0); repeat(3) { writeInt(0) }
                writeShort(video.width); writeShort(video.height)
                writeInt(0x00480000); writeInt(0x00480000); writeInt(0); writeShort(1)
                repeat(32) { writeByte(0) }
                writeShort(0x18); writeShort(-1)
            }.add(box(video.configBox) { write(video.config) })
        } else {
            val a = requireNotNull(audio)
            box("mp4a") {
                repeat(6) { writeByte(0) }; writeShort(1)
                writeInt(0); writeInt(0)
                writeShort(a.channels); writeShort(16); writeShort(0); writeShort(0)
                writeInt(a.sampleRate.coerceAtMost(65535) shl 16)
            }.add(esds(a))
        }
        val stbl = box("stbl")
            .add(fullBox("stsd", 0, 0) { writeInt(1) }.add(entry))
            .add(fullBox("stts", 0, 0) { writeInt(0) })
            .add(fullBox("stsc", 0, 0) { writeInt(0) })
            .add(fullBox("stsz", 0, 0) { writeInt(0); writeInt(0) })
            .add(fullBox("stco", 0, 0) { writeInt(0) })
        val minf = box("minf").add(header).add(dinf).add(stbl)
        val trak = box("trak").add(tkhd).add(box("mdia").add(mdhd).add(hdlr).add(minf))
        val mvex = box("mvex").add(fullBox("trex", 0, 0) { writeInt(1); writeInt(1); writeInt(0); writeInt(0); writeInt(0) })
        val moov = box("moov").add(mvhd).add(trak).add(mvex)
        return ftyp.done() + moov.done()
    }

    private fun esds(a: Audio): Box {
        fun descriptor(tag: Int, body: ByteArray): ByteArray {
            val o = ByteArrayOutputStream()
            o.write(tag)
            // 4-byte size form, as written by most muxers.
            val n = body.size
            o.write(0x80 or ((n ushr 21) and 0x7f)); o.write(0x80 or ((n ushr 14) and 0x7f))
            o.write(0x80 or ((n ushr 7) and 0x7f)); o.write(n and 0x7f)
            o.write(body)
            return o.toByteArray()
        }
        val specific = descriptor(0x05, a.audioSpecificConfig)
        val decoderConfig = ByteArrayOutputStream().apply {
            val d = DataOutputStream(this)
            d.writeByte(0x40) // MPEG-4 audio
            d.writeByte(0x15) // audio stream
            d.writeByte(0); d.writeShort(0) // buffer size
            d.writeInt(192_000); d.writeInt(192_000)
            d.write(specific)
        }.toByteArray()
        val es = ByteArrayOutputStream().apply {
            val d = DataOutputStream(this)
            d.writeShort(1); d.writeByte(0)
            d.write(descriptor(0x04, decoderConfig))
            d.write(descriptor(0x06, byteArrayOf(2)))
        }.toByteArray()
        return fullBox("esds", 0, 0) { write(descriptor(0x03, es)) }
    }

    /**
     * Writes one moof+mdat fragment. [baseDecodeTime] is in the track timescale. Composition offsets
     * may be negative (trun version 1), so decode times never need an artificial shift.
     */
    fun fragment(out: OutputStream, sequence: Int, baseDecodeTime: Long, samples: List<Sample>) {
        require(samples.isNotEmpty()) { "Empty fragment" }
        val video = samples.any { it.compositionOffset != 0 || !it.keyframe }
        val trunFlags = 0x000001 or 0x000100 or 0x000200 or 0x000400 or (if (video) 0x000800 else 0)
        val perSample = 4 + 4 + 4 + (if (video) 4 else 0)
        val trunSize = 12 + 4 + 4 + perSample * samples.size
        val tfhdSize = 16
        val tfdtSize = 20
        val trafSize = 8 + tfhdSize + tfdtSize + trunSize
        val mfhdSize = 16
        val moofSize = 8 + mfhdSize + trafSize
        val d = DataOutputStream(out.buffered(64 * 1024))
        d.writeInt(moofSize); d.writeBytes("moof")
        d.writeInt(mfhdSize); d.writeBytes("mfhd"); d.writeInt(0); d.writeInt(sequence)
        d.writeInt(trafSize); d.writeBytes("traf")
        d.writeInt(tfhdSize); d.writeBytes("tfhd"); d.writeInt(0x020000); d.writeInt(1) // default-base-is-moof
        d.writeInt(tfdtSize); d.writeBytes("tfdt"); d.writeInt(0x01000000); d.writeLong(baseDecodeTime)
        d.writeInt(trunSize); d.writeBytes("trun"); d.writeInt((1 shl 24) or trunFlags)
        d.writeInt(samples.size); d.writeInt(moofSize + 8)
        for (s in samples) {
            d.writeInt(s.duration); d.writeInt(s.data.size)
            d.writeInt(if (s.keyframe) 0x02000000 else 0x01010000)
            if (video) d.writeInt(s.compositionOffset)
        }
        val payload = samples.sumOf { it.data.size.toLong() }
        require(payload + 8 <= Int.MAX_VALUE) { "Fragment too large" }
        d.writeInt((payload + 8).toInt()); d.writeBytes("mdat")
        for (s in samples) d.write(s.data)
        d.flush()
    }

    /** RFC 6381 codec string for an avcC record. */
    fun avcCodec(avcC: ByteArray): String {
        require(avcC.size >= 4) { "Invalid avcC" }
        return String.format("avc1.%02X%02X%02X", avcC[1].toInt() and 0xff, avcC[2].toInt() and 0xff, avcC[3].toInt() and 0xff)
    }

    /** RFC 6381 / ISO 14496-15 codec string for an hvcC record. */
    fun hevcCodec(hvcC: ByteArray, fourcc: String = "hvc1"): String {
        require(hvcC.size >= 13) { "Invalid hvcC" }
        val b1 = hvcC[1].toInt() and 0xff
        val space = b1 ushr 6
        val tier = (b1 ushr 5) and 1
        val profile = b1 and 0x1f
        var compat = 0L
        for (i in 2..5) compat = (compat shl 8) or (hvcC[i].toLong() and 0xff)
        val reversed = java.lang.Long.reverse(compat) ushr 32
        val level = hvcC[12].toInt() and 0xff
        val constraints = (6..11).map { hvcC[it].toInt() and 0xff }.dropLastWhile { it == 0 }
        val sb = StringBuilder(fourcc).append('.')
        if (space > 0) sb.append(('A' + space - 1))
        sb.append(profile).append('.').append(java.lang.Long.toHexString(reversed).uppercase())
            .append('.').append(if (tier == 1) 'H' else 'L').append(level)
        for (c in constraints) sb.append('.').append(Integer.toHexString(c).uppercase())
        return sb.toString()
    }

    /** AudioSpecificConfig for AAC-LC. */
    fun aacConfig(sampleRate: Int, channels: Int): ByteArray {
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        val index = rates.indexOf(sampleRate).takeIf { it >= 0 } ?: throw IllegalArgumentException("Unsupported AAC sample rate")
        return byteArrayOf(((2 shl 3) or (index ushr 1)).toByte(), (((index and 1) shl 7) or (channels shl 3)).toByte())
    }
}
