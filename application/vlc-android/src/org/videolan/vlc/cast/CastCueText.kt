/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import java.util.Locale

/**
 * Turns subtitle cue payloads into safe WebVTT cue text and documents.
 *
 * WebVTT is stricter than SRT/ASS: a blank line ends a cue (later lines were silently lost),
 * "-->" starts a timing line, and '<' / '&' begin markup ("I <3 you" vanished). ASS drawing
 * commands and override tags ({\an8}, {\p1}m 0 0 l ...) also leaked as visible text.
 */
internal object CastCueText {
    data class Cue(val startMs: Long, val endMs: Long?, val text: String)

    private val drawingMode = Regex("""\\p(\d+)""")
    private val override = Regex("""\{[^}]*\}""")

    /** ASS/SSA event text: Matroska stores "ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text". */
    fun fromAss(raw: String, matroska: Boolean = true): String {
        var text = raw.trimEnd('\u0000').trim()
        if (text.startsWith("Dialogue:")) {
            // Script lines: "Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text"
            val fields = text.removePrefix("Dialogue:").trim().split(',', limit = 10)
            if (fields.size == 10) text = fields[9]
        } else if (matroska) {
            val fields = text.split(',', limit = 9)
            if (fields.size == 9) text = fields[8]
        }
        return clean(stripDrawings(text).replace("\\N", "\n").replace("\\n", "\n").replace("\\h", "\u00a0"))
    }

    /** SRT/plain text: keeps <i>/<b>/<u>, drops other tags and stray ASS overrides like {\an8}. */
    fun fromSrt(raw: String): String = clean(raw.trimEnd('\u0000').replace(override, ""))

    /** Removes text drawn in ASS vector mode ({\p1} ... {\p0}) and every override block. */
    private fun stripDrawings(text: String): String {
        val out = StringBuilder()
        var drawing = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{') {
                val close = text.indexOf('}', i)
                if (close < 0) { if (!drawing) out.append(text, i, text.length); break }
                val block = text.substring(i + 1, close)
                val p = drawingMode.findAll(block).lastOrNull()
                if (p != null) drawing = p.groupValues[1] != "0"
                i = close + 1
                continue
            }
            if (!drawing) out.append(c)
            i++
        }
        return out.toString()
    }

    /** Normalises line breaks, removes unsupported tags, escapes markup and drops blank lines. */
    private fun clean(text: String): String = CastVttText.cue(text)

    fun time(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d.%03d",
        ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)

    /** Sorted WebVTT document; cues without an end last until the next cue (max 5 s). */
    fun render(cues: List<Cue>, maxChars: Int = 5 * 1024 * 1024): String {
        val ordered = cues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        require(ordered.isNotEmpty()) { "No readable text captions were found in this track" }
        var chars = 0
        return buildString {
            append("WEBVTT\n\n")
            for (i in ordered.indices) {
                val cue = ordered[i]
                chars += cue.text.length
                require(chars <= maxChars) { "Embedded subtitle track too large" }
                val next = ordered.getOrNull(i + 1)?.startMs
                val end = cue.endMs?.takeIf { it > cue.startMs }
                    ?: minOf(cue.startMs + 5000, next?.takeIf { it > cue.startMs } ?: Long.MAX_VALUE)
                append(time(cue.startMs)).append(" --> ").append(time(maxOf(end, cue.startMs + 1)))
                    .append('\n').append(cue.text).append("\n\n")
            }
        }
    }
}
