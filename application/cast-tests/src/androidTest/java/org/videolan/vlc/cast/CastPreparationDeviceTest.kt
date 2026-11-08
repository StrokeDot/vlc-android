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

    private data class Decoded(val count: Int, val hash: String, val peak: Int, val timestamps: List<Long>)

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
            val timestamps = arrayListOf<Long>()
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
                        count++; timestamps.add(info.presentationTimeUs)
                    }
                    val done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                    if (done) return Decoded(count, digest.digest().joinToString("") { "%02x".format(it) }, peak, timestamps)
                }
            }
            error("Fixture decoding timed out")
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }

    private fun assertSameVideo(input: File, output: File) {
        assertEquals("Original compressed video and timeline", samples(input, "video/"), samples(output, "video/"))
        val expected = decoded(input, "video/"); val actual = decoded(output, "video/")
        assertEquals("Decoded video frame count", expected.count, actual.count)
        assertEquals("Decoded video pixels", expected.hash, actual.hash)
        for (i in expected.timestamps.indices) {
            val a = expected.timestamps[i] - expected.timestamps.first()
            val b = actual.timestamps[i] - actual.timestamps.first()
            assertTrue("Video frame $i presentation interval differs", kotlin.math.abs(a - b) <= 2000L)
        }
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
            assertSameVideo(input, output)
            assertTrue(CastVideoValidation.matches(input, output, {}))
            assertEquals("Expected stable VLC AAC output", "pipelined.mp4", output.name)
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
            val output = CastPipelinedPreparation.prepare(context, input, folder, {}, {}, platformEncoder = true).file
            output.copyTo(File(context.getExternalFilesDir(null), name.removeSuffix(".mkv") + "-output.mp4"), overwrite = true)
            assertTrue(CastVideoValidation.matches(input, output, {}))
            assertSameVideo(input, output)
            assertTrue(decoded(output, "audio/").peak > 256)
            assertEquals(2, folder.listFiles()!!.size)
        } finally { folder.deleteRecursively() }
    }
    @Test fun nativeSinglePassPreservesBFrames() = singlePass("cast-bframes.mkv")
    @Test fun nativeSinglePassPreservesHevc() = singlePass("cast-hevc.mkv")

    @Test fun cancelledNativePreparationLeavesNoCandidateAndKeepsOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "cancel-native-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, "input.mkv")
            instrumentation.context.assets.open("cast-dolby.mkv").use { source -> input.outputStream().use { source.copyTo(it) } }
            try {
                CastAudioPreparation.prepare(context, Uri.fromFile(input), folder, {
                    if (File(folder, "pipelined.mp4").exists()) throw java.util.concurrent.CancellationException("Cancel fixture")
                }, {})
                fail("Preparation must propagate cancellation")
            } catch (_: java.util.concurrent.CancellationException) { }
            assertTrue(input.isFile)
            assertEquals(listOf("input.mkv"), folder.listFiles()!!.map { it.name })
        } finally { folder.deleteRecursively() }
    }

    @Test fun validationRejectsInteriorTimingChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "timeline-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, "input.mkv")
            instrumentation.context.assets.open("cast-dolby.mkv").use { source -> input.outputStream().use { source.copyTo(it) } }
            val prepared = CastAudioPreparation.prepare(context, Uri.fromFile(input), folder, {}, {}).file
            val changed = File(folder, "changed.mp4")
            val extractor = MediaExtractor()
            var muxer: android.media.MediaMuxer? = null
            var started = false
            try {
                extractor.setDataSource(prepared.path)
                val mux = android.media.MediaMuxer(changed.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); muxer = mux
                val tracks = (0 until extractor.trackCount).associateWith { mux.addTrack(extractor.getTrackFormat(it)) }
                val video = tracks.keys.first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                tracks.keys.forEach { extractor.selectTrack(it) }
                val data = ByteBuffer.allocate(1024*1024); val info = MediaCodec.BufferInfo()
                mux.start(); started = true
                var frame = 0
                while (extractor.sampleTime >= 0) {
                    data.clear(); val n = extractor.readSampleData(data, 0); if (n < 0) break
                    val track = extractor.sampleTrackIndex
                    val shift = if (track == video && frame++ == 5) 5000L else 0L
                    info.set(0, n, extractor.sampleTime + shift, extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)
                    mux.writeSampleData(tracks.getValue(track), data, info); extractor.advance()
                }
            } finally { try { if (started) muxer?.stop() } finally { muxer?.release(); extractor.release() } }
            assertFalse("Interior timing changes must reject the candidate", CastVideoValidation.matches(input, changed, {}))
        } finally { folder.deleteRecursively() }
    }

    @Test fun rotatedVideoKeepsOrientationThroughPreparation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "rotation-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, "rotated.mp4")
            instrumentation.context.assets.open("cast-rotated.mp4").use { source -> input.outputStream().use { source.copyTo(it) } }
            val metadata = android.media.MediaMetadataRetriever()
            try { metadata.setDataSource(input.path); assertTrue(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() in listOf(90, 270)) }
            finally { metadata.release() }
            val output = CastAudioPreparation.prepare(context, Uri.fromFile(input), folder, {}, {}).file
            assertTrue(CastVideoValidation.matches(input, output, {}))
            assertSameVideo(input, output)
        } finally { folder.deleteRecursively() }
    }

    @Test fun completedPreparationIsReusedAndInvalidatedWhenSourceChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "prepared-cache-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val movie = File(root, "movie.mkv").apply { writeText("original") }
            val cache = File(root, "cache")
            var preparations = 0
            fun convert(session: String): CastPreparation.Prepared {
                val folder = File(root, session).apply { mkdirs() }
                return CastPreparedCache.getOrPrepare(cache, movie, folder, {}, {}) {
                    val output = File(folder, "prepared.mp4").apply { writeText("conversion ${++preparations}") }
                    CastPreparation.Prepared(output, "video/mp4")
                }
            }
            val first = convert("first")
            CastPreparedCache.remember(first)
            assertFalse(first.file.exists())
            val reused = convert("second")
            assertEquals(1, preparations)
            assertEquals("conversion 1", reused.file.readText())
            CastPreparedCache.remember(reused)
            movie.appendText(" changed")
            val changed = convert("third")
            assertEquals("conversion 2", changed.file.readText())
            CastPreparedCache.remember(changed)
            assertEquals(1, cache.listFiles()!!.size)
            assertEquals("conversion 2", convert("fourth").file.readText())
            assertEquals(2, preparations)
        } finally { root.deleteRecursively() }
    }

    @Test fun embeddedSubRipInRealMatroskaCanBeExtractedToWebVtt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "embedded-subtitle-${System.nanoTime()}").apply { mkdirs() }
        try {
            val movie = File(folder, "embedded-text.mkv")
            instrumentation.context.assets.open("embedded-text.mkv").use { input ->
                movie.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = Uri.fromFile(movie)
            val tracks = CastEmbeddedSubtitles.tracks(context, uri)
            assertTrue("Android must expose the real MKV embedded SRT track", tracks.any { it.format == "SRT" })
            val subtitles = CastEmbeddedSubtitles.extract(context, uri, tracks.first { it.format == "SRT" }.index)
            assertTrue(subtitles.startsWith("WEBVTT\n\n"))
            assertTrue(subtitles.contains("00:00:00.200 --> 00:00:01.100"))
            assertTrue(subtitles.contains("00:00:01.500 --> 00:00:02.600"))
            assertTrue(subtitles.contains("Embedded hello"))
            assertTrue(subtitles.contains("Second subtitle"))
            assertTrue(subtitles.contains("-->"))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun compareTwoMinutePreparationPaths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "conversion-timing-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(folder, "input.mkv")
            java.util.zip.GZIPInputStream(instrumentation.context.assets.open("cast-long.fixture")).use { source -> input.outputStream().use { source.copyTo(it) } }
            val legacy = File(folder, "legacy").apply { mkdirs() }
            val fast = File(folder, "fast").apply { mkdirs() }
            val started = android.os.SystemClock.elapsedRealtime()
            val experimental = CastPipelinedPreparation.prepare(context, input, legacy, {}, {}, platformEncoder = true).file
            val experimentalDuration = android.os.SystemClock.elapsedRealtime() - started
            val begin = android.os.SystemClock.elapsedRealtime()
            val output = CastAudioPreparation.prepare(context, Uri.fromFile(input), fast, {}, {}).file
            val stableDuration = android.os.SystemClock.elapsedRealtime() - begin
            android.util.Log.i("VLCPlus/CastPerf", "fixture=120s-60fps pcmExperimentalMs=$experimentalDuration stableVlcAacMs=$stableDuration inputBytes=${input.length()}")
            assertEquals("pipelined.mp4", output.name)
            assertTrue(CastVideoValidation.matches(input, experimental, {}))
            assertTrue(CastVideoValidation.matches(input, output, {}))
            assertTrue(decoded(output, "audio/").peak > 256)
            val completeAudio = samples(output, "audio/")
            assertTrue("Two-minute audio must be complete", completeAudio.count > 5500 && completeAudio.last - completeAudio.first > 119_000_000L)
            val seeker = MediaExtractor()
            try {
                seeker.setDataSource(output.path)
                val video = (0 until seeker.trackCount).first { seeker.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                seeker.selectTrack(video); seeker.seekTo(90_000_000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                assertTrue("Indexed prepared MP4 must seek near 90 seconds", kotlin.math.abs(seeker.sampleTime - 90_000_000L) < 1_100_000L)
            } finally { seeker.release() }
        } finally { folder.deleteRecursively() }
    }

}
