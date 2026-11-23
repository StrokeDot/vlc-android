package org.videolan.vlc.cast

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** Streaming conversion (HLS/fMP4) against Android's own extractors, codecs and libVLC. */
@RunWith(AndroidJUnit4::class)
class CastHlsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun <T> session(asset: String, block: (CastHlsSession, File, File) -> T): T {
        val folder = File(context.cacheDir, "hls-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, asset)
            instrumentation.context.assets.open(asset).use { s -> input.outputStream().use { s.copyTo(it) } }
            val session = requireNotNull(CastHlsSession.open(context, Uri.fromFile(input), File(folder, "segments").apply { mkdirs() }))
            return session.use { block(it, input, folder) }
        } finally { folder.deleteRecursively() }
    }

    /** init + every segment of one rendition, concatenated into a single fragmented MP4. */
    private fun rendition(session: CastHlsSession, audio: Boolean, out: File): File {
        out.outputStream().use { o ->
            session.resolve(if (audio) "audio-init.mp4" else "video-init.mp4")!!.open(0).use { it.copyTo(o) }
            for (i in session.hls.segments.indices) session.resolve("${if (audio) "a" else "v"}$i.m4s")!!.open(0).use { it.copyTo(o) }
        }
        return out
    }

    private data class Track(val count: Int, val first: Long, val last: Long, val mime: String)

    private fun track(file: File, prefix: String): Track {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }
            extractor.selectTrack(index)
            val times = ArrayList<Long>()
            val data = ByteBuffer.allocate(4 * 1024 * 1024)
            while (extractor.sampleTrackIndex >= 0) {
                data.clear(); if (extractor.readSampleData(data, 0) < 0) break
                times += extractor.sampleTime; extractor.advance()
            }
            return Track(times.size, times.minOrNull() ?: -1, times.maxOrNull() ?: -1, extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)!!)
        } finally { extractor.release() }
    }

    private fun peak(file: File): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val format = extractor.getTrackFormat(0)
            extractor.selectTrack(0)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var peak = 0
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val b = decoder.getOutputBuffer(o)!!.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    b.position(info.offset); b.limit(info.offset + info.size)
                    while (b.remaining() >= 2) peak = maxOf(peak, kotlin.math.abs(b.short.toInt()))
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return peak
        } finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release() }
    }

    @Test fun streamingCopiesEveryVideoSampleWithItsTimestamp() = session("cast-bframes.mkv") { session, input, folder ->
        val source = track(input, "video/")
        val stream = track(rendition(session, false, File(folder, "video.mp4")), "video/")
        assertEquals(source.mime, stream.mime)
        assertEquals("Every video sample must be copied", source.count, stream.count)
        assertTrue("first ${source.first} vs ${stream.first}", kotlin.math.abs(source.first - stream.first) <= 1000)
        assertTrue("last ${source.last} vs ${stream.last}", kotlin.math.abs(source.last - stream.last) <= 1000)
        val master = session.resolve("master.m3u8")!!.open(0).use { it.readBytes().decodeToString() }
        assertTrue(master, master.contains("CODECS=\"avc1.") && master.contains("mp4a.40.2"))
    }

    @Test fun streamingConvertsSurroundAudioToAudibleStereoAac() = session("cast-dolby.mkv") { session, input, folder ->
        assertNotEquals(CastHlsSession.AudioMode.COPY, session.audioMode)
        val audio = rendition(session, true, File(folder, "audio.mp4"))
        val stream = track(audio, "audio/")
        assertEquals("audio/mp4a-latm", stream.mime)
        val video = track(input, "video/")
        // The audio rendition must span the movie; segments are filled to the playlist duration.
        assertTrue("audio ${stream.first}..${stream.last} vs video ${video.first}..${video.last}",
            stream.last - stream.first > (video.last - video.first) * 8 / 10)
        assertTrue("Converted audio must contain an audible signal", peak(audio) > 256)
    }
}
