package org.videolan.vlc.cast

import java.io.File
import java.io.FileInputStream

/** JVM check of embedded subtitle extraction on ffmpeg- and mkvmerge-made files (see test-matroska.sh). */
internal fun open(path: String): CastMkvReader { val s = FileInputStream(File(path)); return CastMkvReader(CastMkvReader.ChannelSource(s.channel, s)) }

fun main(args: Array<String>) {
    val dir = args[0]
    var checks = 0
    fun check(ok: Boolean, what: () -> String) { checks++; if (!ok) throw AssertionError(what()) }
    for (name in listOf("ff.mkv", "mm.mkv")) {
        open("$dir/$name").use { reader ->
            val tracks = CastMkvSubtitles.tracks(reader)
            check(tracks.map { it.format } == listOf("SRT", "ASS/SSA")) { "$name tracks: $tracks" }
            val srt = CastMkvSubtitles.extract(reader, tracks[0].number)
            val ass = CastMkvSubtitles.extract(reader, tracks[1].number)
            if (args.size > 1) println("== $name SRT\n$srt== $name ASS\n$ass")
            check("00:00:01.000 --> 00:00:02.500\nI &lt;3 you &amp; me" in srt) { "$name escaping: $srt" }
            check("00:00:03.000 --> 00:00:04.000\nTop text" in srt) { "$name override tag: $srt" }
            check("Red and <i>italic</i>" in srt) { "$name font tag: $srt" }
            check("a --&gt; b\nsecond line" in srt) { "$name arrow/multiline: $srt" }
            check("Bold, with comma" in ass) { "$name ASS text field: $ass" }
            check("m 0 0" !in ass && "00:00:03.000" !in ass) { "$name ASS drawing leaked: $ass" }
            check("Line1\nLine3" in ass) { "$name ASS blank line: $ass" }
            check("Sign text" in ass) { "$name inline drawing: $ass" }
            check(reader.durationNs in 9_000_000_000L..11_000_000_000L) { "$name duration ${reader.durationNs}" }
        }
    }
    open("$dir/mm.mkv").use { r ->
        check(r.tracks.first { it.isSubtitle }.encodings.any { it.algorithm == 0L }) { "zlib encoding not detected" }
        check(r.cues().any { it.track == 3 && it.relative != null }) { "subtitle cues missing" }
        val t = CastMkvSubtitles.tracks(r)
        check(t[0].name == "English SDH" && !t[0].forced && t[1].forced) { "names/flags $t" }
    }
    println("Matroska checks: $checks passed")
}
