/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

/**
 * Embedded Matroska text subtitles (SRT, ASS/SSA, WebVTT) via [CastMkvReader].
 * Uses the Cues index when it covers the subtitle track (mkvmerge does this by default), so only
 * the subtitle blocks are read; otherwise one header-only pass over the clusters.
 * Android-independent; [CastMatroskaText] opens files for it.
 */
internal object CastMkvSubtitles {
    data class Track(val number: Int, val language: String, val format: String, val name: String = "", val forced: Boolean = false)

    private fun format(codec: String) = when (codec) {
        "S_TEXT/UTF8", "S_TEXT/ASCII" -> "SRT"
        "S_TEXT/ASS", "S_TEXT/SSA", "S_ASS", "S_SSA" -> "ASS/SSA"
        "S_TEXT/WEBVTT" -> "WebVTT"
        else -> null
    }

    fun tracks(reader: CastMkvReader): List<Track> = reader.tracks.mapNotNull { t ->
        val format = format(t.codecId) ?: return@mapNotNull null
        if (!t.isSubtitle || t.encrypted) null else Track(t.number, t.language, format, t.name, t.forced)
    }

    fun extract(reader: CastMkvReader, trackNumber: Int, interrupted: () -> Boolean = { Thread.currentThread().isInterrupted }): String {
        val track = tracks(reader).firstOrNull { it.number == trackNumber }
            ?: error("This movie does not have the selected embedded text track")
        val cues = ArrayList<CastCueText.Cue>()
        var blocksRead = 0
        fun add(block: CastMkvReader.Block) {
            if (interrupted()) throw InterruptedException("Subtitle extraction cancelled")
            blocksRead++
            val raw = String(block.frames.firstOrNull() ?: return, Charsets.UTF_8)
            val text = if (track.format == "ASS/SSA") CastCueText.fromAss(raw) else CastCueText.fromSrt(raw)
            if (text.isBlank()) return
            require(cues.size < 100_000) { "Too many embedded subtitle cues" }
            val start = block.timeNs / 1_000_000
            cues += CastCueText.Cue(start, block.durationNs?.let { start + it / 1_000_000 }, text)
        }
        val wanted = setOf(track.number)
        val indexed = reader.cues().filter { it.track == track.number }
        if (indexed.isNotEmpty() && indexed.all { it.relative != null }) {
            val clusterTimes = HashMap<Long, Long?>()
            for (point in indexed) {
                val clusterTime = clusterTimes.getOrPut(point.cluster) { reader.clusterTime(point.cluster) } ?: continue
                val clusterData = clusterData(reader, point.cluster) ?: continue
                reader.blockAt(clusterData + point.relative!!, clusterTime, wanted)?.let(::add)
            }
            // A partial index (some muxers only index a few subtitle blocks) falls back to a full pass.
            if (blocksRead >= indexed.size) return CastCueText.render(cues)
            cues.clear(); blocksRead = 0
        }
        require(reader.firstCluster >= 0) { "This movie has no media clusters" }
        reader.blocks(reader.firstCluster, wanted) { add(it); true }
        return CastCueText.render(cues)
    }

    /** CueRelativePosition is relative to the cluster's data start. */
    private fun clusterData(reader: CastMkvReader, cluster: Long): Long? {
        val header = reader.bytes(cluster, minOf(12L, reader.length - cluster).toInt())
        if (header.size < 5) return null
        var idLength = 1
        val first = header[0].toInt() and 0xff
        while (idLength <= 4 && first and (0x80 shr (idLength - 1)) == 0) idLength++
        val sizeByte = header[idLength].toInt() and 0xff
        var sizeLength = 1
        while (sizeLength <= 8 && sizeByte and (0x80 shr (sizeLength - 1)) == 0) sizeLength++
        return cluster + idLength + sizeLength
    }
}
