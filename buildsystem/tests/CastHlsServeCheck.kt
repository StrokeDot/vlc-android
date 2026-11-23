package org.videolan.vlc.cast

import java.io.File
import java.net.InetAddress

/** Serves a movie through CastHlsSession + CastFileServer and lets ffmpeg play it over HTTP. */
fun main(args: Array<String>) {
    val movie = File(args[0]); val dir = File(args[1]).apply { deleteRecursively(); mkdirs() }
    val started = System.nanoTime()
    val session = requireNotNull(CastHlsSession.open(android.content.Context(), android.net.Uri("file", movie.path), dir)) { "session unavailable" }
    println("mode=${session.audioMode} openMs=${(System.nanoTime() - started) / 1_000_000}")
    session.use {
        CastFileServer(InetAddress.getLoopbackAddress(), movie, "video/x-matroska").use { server ->
            val url = server.setRouter(session)
            val first = System.nanoTime()
            val probe = ProcessBuilder("ffmpeg", "-v", "error", "-i", url, "-t", "1", "-f", "null", "-").redirectErrorStream(true).start()
            val probeOut = probe.inputStream.readBytes().decodeToString(); probe.waitFor()
            println("firstSecondDecodedMs=${(System.nanoTime() - first) / 1_000_000} ${probeOut.trim()}")
            val full = ProcessBuilder("ffmpeg", "-v", "error", "-i", url, "-f", "null", "-").redirectErrorStream(true).start()
            val out = full.inputStream.readBytes().decodeToString(); val code = full.waitFor()
            check(code == 0 && out.isBlank()) { "ffmpeg failed ($code): $out" }
            val duration = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", url).start()
                .inputStream.readBytes().decodeToString().trim()
            println("fullDecode=ok duration=$duration")
        }
    }
}
