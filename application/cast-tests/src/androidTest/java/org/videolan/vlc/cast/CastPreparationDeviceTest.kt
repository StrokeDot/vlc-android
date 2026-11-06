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
import java.security.MessageDigest

/** Exercises the actual production preparation source against Android codecs. */
@RunWith(AndroidJUnit4::class)
class CastPreparationDeviceTest {
    private data class Samples(val count: Int, val hash: String, val first: Long, val last: Long, val mime: String)

    private fun samples(file: File, prefix: String): Samples {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }
            val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(track)
            val data = ByteBuffer.allocate(1024 * 1024)
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0; var first = -1L; var last = -1L
            while (extractor.sampleTime >= 0) {
                data.clear(); val n = extractor.readSampleData(data, 0); if (n < 0) break
                data.position(0); data.limit(n); digest.update(data)
                if (first < 0) first = extractor.sampleTime
                last = extractor.sampleTime; count++; extractor.advance()
            }
            return Samples(count, digest.digest().joinToString("") { "%02x".format(it) }, first, last, mime)
        } finally { extractor.release() }
    }

    private data class Decoded(val count: Int, val hash: String, val peak: Int)

    /** Decode the AAC fixture too: an encoded track must contain audible PCM. */
    private fun decoded(file: File, prefix: String): Decoded {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith(prefix) }
            val format = extractor.getTrackFormat(track)
            if (prefix == "audio/") format.setInteger("pcm-encoding", android.media.AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder; extractor.selectTrack(track)
            decoder.configure(format, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo()
            val digest = MessageDigest.getInstance("SHA-256")
            var inputDone = false; var count = 0; var peak = 0
            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!; buffer.clear()
                        val n = extractor.readSampleData(buffer, 0)
                        if (n < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(index, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10000)
                if (index >= 0) {
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(index)!!.duplicate().apply {
                            position(info.offset); limit(info.offset + info.size)
                        }
                        digest.update(buffer.duplicate())
                        if (prefix == "audio/") {
                            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            while (buffer.remaining() >= 2) peak = maxOf(peak, kotlin.math.abs(buffer.short.toInt()))
                        }
                        count++
                    }
                    val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                    if (done) return Decoded(count, digest.digest().joinToString("") { "%02x".format(it) }, peak)
                }
            }
            error("Fixture decoding timed out")
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }

    private fun fixture(name: String, forceAudio: Boolean, verify: (File, File) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "cast-fixture-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, name)
            instrumentation.context.assets.open(name).use { source -> input.outputStream().use { source.copyTo(it) } }
            val prepared = CastPreparation.prepare(context, Uri.fromFile(input), folder, forceAudio, {}, {})
            assertEquals("video/mp4", prepared.mime)
            assertNotEquals(input.path, prepared.file.path)
            assertTrue(prepared.file.length() > 0)
            verify(input, prepared.file)
        } finally { folder.deleteRecursively() }
    }

    @Test fun audioConversionPreservesVideoSamplesAndSeekTimeline() = fixture("cast-fixture.mp4", true) { input, output ->
        assertEquals(samples(input, "video/"), samples(output, "video/"))
        val audio = samples(output, "audio/")
        assertEquals("audio/mp4a-latm", audio.mime)
        assertTrue(audio.count > 50)
        assertTrue(audio.last - audio.first > 1_700_000)
    }

    @Test fun containerPreparationCopiesVideoAndCompatibleAudio() = fixture("cast-fixture.mkv", false) { input, output ->
        assertEquals(samples(input, "video/"), samples(output, "video/"))
        assertEquals(samples(input, "audio/").hash, samples(output, "audio/").hash)
    }

    @Test fun dolbyMatroskaKeepsVideoAndProducesRealStereoAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "cast-dolby-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, "dolby.mkv")
            instrumentation.context.assets.open("cast-dolby.mkv").use { source -> input.outputStream().use { source.copyTo(it) } }
            val output = CastAudioPreparation.prepare(context, Uri.fromFile(input), folder, {}, {}).file
            output.copyTo(File(context.getExternalFilesDir(null), "cast-dolby-output.mp4"), overwrite = true)
            assertEquals(decoded(input, "video/"), decoded(output, "video/"))
            assertTrue(CastVideoValidation.matches(input, output, {}))
            assertEquals("Expected one-pass output", "native-prepared.mp4", output.name)
            val audio = samples(output, "audio/")
            assertEquals("audio/mp4a-latm", audio.mime)
            assertTrue("Converted audio must contain an audible signal", decoded(output, "audio/").peak > 256)
            assertTrue(audio.count > 10)
            assertTrue(audio.last - audio.first > 1_700_000)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(output.path)
                val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
                assertEquals(2, extractor.getTrackFormat(track).getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } finally { extractor.release() }
        } finally { folder.deleteRecursively() }
    }
    private fun singlePass(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "single-pass-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, name)
            instrumentation.context.assets.open(name).use { source -> input.outputStream().use { source.copyTo(it) } }
            val output = File(folder, "output.mp4")
            CastNativePreparation.convert(context, input, output, true, {}, {})
            assertTrue(CastVideoValidation.matches(input, output, {}))
            assertEquals(decoded(input, "video/"), decoded(output, "video/"))
            assertTrue(decoded(output, "audio/").peak > 256)
            assertEquals(2, folder.listFiles()!!.size)
        } finally { folder.deleteRecursively() }
    }
    @Test fun nativeSinglePassPreservesBFrames() = singlePass("cast-bframes.mkv")
    @Test fun nativeSinglePassPreservesHevc() = singlePass("cast-hevc.mkv")

}
