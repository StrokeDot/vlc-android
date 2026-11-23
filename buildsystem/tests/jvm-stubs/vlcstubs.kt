@file:Suppress("UNUSED_PARAMETER", "unused")
package org.videolan.vlc.cast
/** Test stand-in for VLC: writes a pre-made AAC TS progressively, like a running transcode. */
internal object CastNativePreparation {
    fun convert(context: android.content.Context, input: java.io.File, output: java.io.File, streaming: Boolean, check: () -> Unit, progress: (String) -> Unit, fastAudio: Boolean = streaming, pcm: Boolean = false): java.io.File {
        val source = java.io.File(System.getProperty("fakeTs"))
        java.io.FileOutputStream(output, true).use { out -> source.inputStream().use { inp ->
            val buf = ByteArray(16 * 1024); while (true) { check(); val n = inp.read(buf); if (n < 0) break; out.write(buf, 0, n); out.flush(); Thread.sleep(2) } } }
        return output
    }
}
internal object CastMovieLocation { fun localFile(context: android.content.Context, uri: android.net.Uri): android.net.Uri? = uri }
