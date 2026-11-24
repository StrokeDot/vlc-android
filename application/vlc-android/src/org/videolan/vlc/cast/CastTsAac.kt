/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import java.io.ByteArrayOutputStream

/**
 * Incremental MPEG-TS reader that yields raw AAC frames (ADTS headers removed) from the first
 * ADTS audio stream. Fed with VLC's growing transcode output, so frames become available while
 * the conversion is still running. Android-independent.
 */
internal class CastTsAac(private val onFrame: (frame: ByteArray, sampleRate: Int, channels: Int) -> Unit) {
    private val pending = ByteArrayOutputStream()
    private var carry = ByteArray(0)
    private var pmtPid = -1
    private var audioPid = -1
    private val pes = ByteArrayOutputStream()
    private var adts = ByteArray(0)
    var frames = 0L; private set

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var buffer = if (carry.isEmpty()) data.copyOfRange(offset, offset + length) else carry + data.copyOfRange(offset, offset + length)
        var pos = 0
        while (buffer.size - pos >= PACKET) {
            if (buffer[pos] != SYNC) { pos++; continue } // resynchronise
            packet(buffer, pos)
            pos += PACKET
        }
        carry = buffer.copyOfRange(pos, buffer.size)
    }

    /** Flushes the last PES at end of stream. */
    fun finish() { flushPes() }

    private fun packet(b: ByteArray, at: Int) {
        val start = b[at + 1].toInt() and 0x40 != 0
        val pid = ((b[at + 1].toInt() and 0x1f) shl 8) or (b[at + 2].toInt() and 0xff)
        val control = (b[at + 3].toInt() ushr 4) and 3
        var p = at + 4
        if (control == 2 || control == 0) return
        if (control == 3) p += 1 + (b[at + 4].toInt() and 0xff)
        if (p >= at + PACKET) return
        when {
            pid == 0 -> if (start) parsePat(b, p + 1 + (b[p].toInt() and 0xff), at + PACKET)
            pid == pmtPid -> if (start) parsePmt(b, p + 1 + (b[p].toInt() and 0xff), at + PACKET)
            pid == audioPid -> {
                if (start) flushPes()
                pes.write(b, p, at + PACKET - p)
            }
        }
    }

    private fun parsePat(b: ByteArray, p: Int, end: Int) {
        if (p + 12 > end) return
        val sectionEnd = minOf(end, p + 3 + (((b[p + 1].toInt() and 0x0f) shl 8) or (b[p + 2].toInt() and 0xff)) - 4)
        var i = p + 8
        while (i + 4 <= sectionEnd) {
            val program = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
            val pid = ((b[i + 2].toInt() and 0x1f) shl 8) or (b[i + 3].toInt() and 0xff)
            if (program != 0) { pmtPid = pid; return }
            i += 4
        }
    }

    private fun parsePmt(b: ByteArray, p: Int, end: Int) {
        if (p + 12 > end) return
        val sectionEnd = minOf(end, p + 3 + (((b[p + 1].toInt() and 0x0f) shl 8) or (b[p + 2].toInt() and 0xff)) - 4)
        val infoLength = ((b[p + 10].toInt() and 0x0f) shl 8) or (b[p + 11].toInt() and 0xff)
        var i = p + 12 + infoLength
        while (i + 5 <= sectionEnd) {
            val type = b[i].toInt() and 0xff
            val pid = ((b[i + 1].toInt() and 0x1f) shl 8) or (b[i + 2].toInt() and 0xff)
            val esInfo = ((b[i + 3].toInt() and 0x0f) shl 8) or (b[i + 4].toInt() and 0xff)
            if (type == 0x0f && audioPid < 0) audioPid = pid
            i += 5 + esInfo
        }
    }

    private fun flushPes() {
        val data = pes.toByteArray(); pes.reset()
        if (data.size < 9 || data[0].toInt() != 0 || data[1].toInt() != 0 || data[2].toInt() != 1) return
        val headerLength = data[8].toInt() and 0xff
        val payload = 9 + headerLength
        if (payload <= data.size) adtsData(data, payload, data.size - payload)
    }

    private fun adtsData(data: ByteArray, offset: Int, length: Int) {
        val b = adts + data.copyOfRange(offset, offset + length)
        var i = 0
        while (b.size - i >= 7) {
            if (b[i].toInt() and 0xff != 0xff || b[i + 1].toInt() and 0xf6 != 0xf0) { i++; continue }
            val protectionAbsent = b[i + 1].toInt() and 1 == 1
            val frameLength = ((b[i + 3].toInt() and 3) shl 11) or ((b[i + 4].toInt() and 0xff) shl 3) or ((b[i + 5].toInt() and 0xff) ushr 5)
            val header = if (protectionAbsent) 7 else 9
            if (frameLength < header) { i++; continue }
            if (b.size - i < frameLength) break
            val rateIndex = (b[i + 2].toInt() ushr 2) and 0x0f
            val channels = ((b[i + 2].toInt() and 1) shl 2) or ((b[i + 3].toInt() and 0xff) ushr 6)
            val rate = RATES.getOrElse(rateIndex) { 48000 }
            frames++
            onFrame(b.copyOfRange(i + header, i + frameLength), rate, channels)
            i += frameLength
        }
        adts = b.copyOfRange(i, b.size)
    }

    companion object {
        private const val PACKET = 188
        private const val SYNC: Byte = 0x47
        private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
    }
}
