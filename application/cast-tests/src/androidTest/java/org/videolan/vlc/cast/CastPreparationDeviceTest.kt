package org.videolan.vlc.cast

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
}
