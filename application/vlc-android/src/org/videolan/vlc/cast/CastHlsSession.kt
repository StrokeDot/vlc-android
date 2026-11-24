/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streaming conversion: serves a Matroska movie to the receiver as HLS (fMP4) while converting it.
 *
 * Video segments are cut from the original file on request (milliseconds each), so the TV starts
 * after the first few seconds of audio are ready instead of after a full-file rewrite. Audio is
 * copied when it is already AAC-LC; otherwise it is transcoded ahead of the playhead with Android's
 * decoders (seekable: a jump restarts the transcoder there) or with VLC for formats Android cannot
 * decode (sequential). Any problem makes [open] return null so the caller uses full conversion.
 */
internal class CastHlsSession private constructor(
    private val context: Context,
    private val opener: () -> CastMkvReader,
    private val localFile: File?,
    private val directory: File,
    private val fileBytes: Long
) : Closeable, CastFileServer.Router {
    enum class AudioMode { NONE, COPY, CODEC, VLC }

    private val videoReader = opener()
    val hls: CastMkvHls
    val audioMode: AudioMode
    private val closed = AtomicBoolean(false)
    private val videoFiles = ConcurrentHashMap<Int, File>()
    private val audioFiles = ConcurrentHashMap<Int, File>()
    private val readerLock = Any() // CastMkvReader is not thread-safe; video and copied audio share it.
    private val audioLock = Object()
    private val prefetch = Executors.newSingleThreadExecutor { r -> Thread(r, "VLCPlus-HLS-prefetch").apply { isDaemon = true } }
    @Volatile private var producer: AudioProducer? = null
    @Volatile var lastError: String? = null; private set
    private val vlcStartFrame: Long

    init {
        try {
            val video = requireNotNull(CastMkvHls.pickVideo(videoReader)) { "No castable H.264/HEVC video track" }
            val audio = CastMkvHls.pickAudio(videoReader)
            hls = CastMkvHls(videoReader, video, audio)
            audioMode = when {
                audio == null -> AudioMode.NONE
                hls.audioCopy -> AudioMode.COPY
                decoderFormat(audio) != null -> AudioMode.CODEC
                localFile != null -> AudioMode.VLC
                else -> throw IllegalStateException("This audio needs full conversion")
            }
            if (audioMode == AudioMode.VLC) hls.useTranscodedAudio(48000)
            vlcStartFrame = if (audioMode == AudioMode.VLC) hls.transcodedStartFrame() else 0L
            if (audioMode == AudioMode.CODEC) hls.useTranscodedAudio(codecOutputRate(audio!!))
            Log.i(PERF, "path=hls segments=${hls.segments.size} video=${hls.videoCodec} audio=$audioMode")
        } catch (e: Exception) { videoReader.close(); throw e }
    }

    // ---- routing ---------------------------------------------------------------------------

    override fun resolve(name: String): CastFileServer.Resource? {
        if (closed.get()) return null
        return when {
            name == "master.m3u8" -> CastFileServer.bytes(hls.masterPlaylist(fileBytes).toByteArray(), PLAYLIST)
            name == "video.m3u8" -> CastFileServer.bytes(hls.mediaPlaylist(false).toByteArray(), PLAYLIST)
            name == "audio.m3u8" && audioMode != AudioMode.NONE -> CastFileServer.bytes(hls.mediaPlaylist(true).toByteArray(), PLAYLIST)
            name == "video-init.mp4" -> CastFileServer.bytes(CastFmp4.init(hls.videoConfig), "video/mp4")
            name == "audio-init.mp4" && audioMode != AudioMode.NONE -> CastFileServer.bytes(CastFmp4.init(requireNotNull(hls.audioConfig)), "audio/mp4")
            name.startsWith("v") && name.endsWith(".m4s") -> index(name)?.let { CastFileServer.file(video(it), "video/mp4") }
            name.startsWith("a") && name.endsWith(".m4s") && audioMode != AudioMode.NONE -> index(name)?.let { CastFileServer.file(audio(it), "audio/mp4") }
            else -> null
        }
    }

    private fun index(name: String) = name.substring(1, name.length - 4).toIntOrNull()?.takeIf { it in hls.segments.indices }

    // ---- video -----------------------------------------------------------------------------

    private fun video(index: Int): File {
        val file = synchronized(readerLock) { videoSegment(index) }
        // The receiver asks for segments in order; have the next one ready.
        if (index + 1 < hls.segments.size && !videoFiles.containsKey(index + 1))
            runCatching { prefetch.execute { runCatching { synchronized(readerLock) { videoSegment(index + 1) } } } }
        return file
    }

    private fun videoSegment(index: Int): File {
        videoFiles[index]?.takeIf { it.isFile }?.let { return it }
        if (closed.get()) throw IOException("Streaming stopped")
        val started = SystemClock.elapsedRealtime()
        val file = File(directory, "v$index.m4s")
        val partial = File(directory, "v$index.part")
        partial.outputStream().use { hls.writeVideo(index, it) }
        if (!partial.renameTo(file)) throw IOException("Could not store a video segment")
        videoFiles[index] = file
        evict(videoFiles, index)
        if (index < 2) Log.i(PERF, "phase=hls-video-segment index=$index ms=${SystemClock.elapsedRealtime() - started} bytes=${file.length()}")
        return file
    }

    private fun evict(files: ConcurrentHashMap<Int, File>, current: Int) {
        // Keep a small window around the playhead; seeking back simply rebuilds a segment.
        for (key in files.keys) if (key < current - KEEP_BEHIND || key > current + KEEP_AHEAD) files.remove(key)?.delete()
    }

    // ---- audio -----------------------------------------------------------------------------

    private fun audio(index: Int): File {
        audioFiles[index]?.takeIf { it.isFile }?.let { return it }
        if (audioMode == AudioMode.COPY) synchronized(readerLock) {
            audioFiles[index]?.takeIf { it.isFile }?.let { return it }
            return store(index) { out -> hls.writeAudioCopy(index, out) }
        }
        ensureProducer(index)
        val deadline = SystemClock.elapsedRealtime() + AUDIO_WAIT_MS
        synchronized(audioLock) {
            while (true) {
                audioFiles[index]?.takeIf { it.isFile }?.let { return it }
                lastError?.let { throw IOException(it) }
                if (closed.get()) throw IOException("Streaming stopped")
                val left = deadline - SystemClock.elapsedRealtime()
                // 503 makes the receiver retry; a long VLC catch-up after a far seek stays recoverable.
                if (left <= 0) throw IOException("Audio segment $index is not ready yet")
                audioLock.wait(left)
            }
        }
    }

    private fun store(index: Int, write: (java.io.OutputStream) -> Unit): File {
        val file = File(directory, "a$index.m4s")
        val partial = File(directory, "a$index.part")
        partial.outputStream().use(write)
        if (!partial.renameTo(file)) throw IOException("Could not store an audio segment")
        audioFiles[index] = file
        evict(audioFiles, index)
        return file
    }

    private fun ensureProducer(index: Int) {
        synchronized(this) {
            val current = producer
            if (current != null && current.alive) {
                // VLC can only run forward; Android decoders restart where the receiver jumped to.
                if (audioMode == AudioMode.VLC || index in current.start..current.position + RESTART_DISTANCE) return
                current.stop()
            }
            producer = (if (audioMode == AudioMode.CODEC) CodecProducer(index) else VlcProducer()).also { it.begin() }
        }
    }

    /** Collects grid-numbered AAC frames and writes each audio segment as soon as it is complete. */
    private abstract inner class AudioProducer(val start: Int) {
        private val thread = Thread({ run() }, "VLCPlus-HLS-audio")
        private val stopped = AtomicBoolean(false)
        @Volatile var position = start; private set
        val alive get() = thread.isAlive && !stopped.get()
        private val frames = HashMap<Long, ByteArray>()
        fun begin() { thread.isDaemon = true; thread.start() }
        fun stop() { stopped.set(true); thread.interrupt(); stopWork() }
        protected fun check() { if (stopped.get() || closed.get() || Thread.currentThread().isInterrupted) throw InterruptedException() }
        protected open fun stopWork() {}
        protected abstract fun produce()

        protected fun frame(n: Long, data: ByteArray) {
            if (position >= hls.segments.size) return
            val range = hls.audioFrames(position)
            if (n < range.first) return
            frames[n] = data
            while (position < hls.segments.size && n >= hls.audioFrames(position).last) flushSegment()
        }

        private fun flushSegment() {
            val index = position
            val range = hls.audioFrames(index)
            if (!audioFiles.containsKey(index)) store(index) { out -> hls.writeAudio(index, out) { frames[it] } }
            for (k in range) frames.remove(k)
            position++
            synchronized(audioLock) { audioLock.notifyAll() }
        }

        private fun run() {
            val started = SystemClock.elapsedRealtime()
            try {
                produce()
                while (position < hls.segments.size) { check(); flushSegment() } // tail: silence-filled
                Log.i(PERF, "phase=hls-audio-done mode=$audioMode from=$start ms=${SystemClock.elapsedRealtime() - started}")
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                if (!stopped.get() && !closed.get()) {
                    Log.w(PERF, "phase=hls-audio-failed mode=$audioMode", e)
                    lastError = e.message ?: "Audio conversion failed"
                }
            } finally {
                stopWork()
                synchronized(audioLock) { audioLock.notifyAll() }
            }
        }
    }

    /** Android decoder → stereo PCM → AAC encoder, starting exactly at segment [start]. */
    private inner class CodecProducer(start: Int) : AudioProducer(start) {
        private inner class Packet(val timeUs: Long, val data: ByteArray?)
        private var decoder: MediaCodec? = null
        private var encoder: MediaCodec? = null
        private val reader = opener()
        private val queue = java.util.concurrent.LinkedBlockingQueue<Packet>(64)
        @Volatile private var reading = true
        private var nextLabel = 0L
        private var pcmStart = 0L
        private var pcmWritten = 0L
        private var rate = 48000
        private var channels = 0
        private var mixIn = ShortArray(0)
        private var mixOut = ShortArray(0)
        private val info = MediaCodec.BufferInfo()

        override fun stopWork() {
            reading = false
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }; decoder = null
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }; encoder = null
            runCatching { reader.close() }
        }

        override fun produce() {
            val track = requireNotNull(hls.audio)
            val format = requireNotNull(decoderFormat(track))
            rate = requireNotNull(hls.audioConfig).sampleRate
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { decoder = it }
            dec.configure(format, null, null, 0); dec.start()
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { encoder = it }
            enc.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            // PCM starts ENCODER_DELAY samples before the segment's first grid frame: the encoder's
            // priming falls on frames that are labelled before the segment and discarded.
            val firstFrame = hls.audioFrames(start).first
            nextLabel = firstFrame - ENCODER_DELAY / CastMkvHls.AAC_FRAME
            pcmStart = hls.audioFrameTime(nextLabel).coerceAtLeast(0)
            val readerThread = Thread({ readPackets(track) }, "VLCPlus-HLS-demux").apply { isDaemon = true; start() }
            try {
                var inputDone = false
                var decoderDone = false
                var idleSince = SystemClock.elapsedRealtime()
                while (true) {
                    check()
                    var advanced = false
                    if (!inputDone) {
                        val packet = queue.poll(5, TimeUnit.MILLISECONDS)
                        if (packet != null) {
                            var index: Int
                            while (true) { check(); index = dec.dequeueInputBuffer(5_000); if (index >= 0) break; decode(dec, enc) }
                            val data = packet.data
                            if (data == null) {
                                dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                            } else {
                                val buffer = requireNotNull(dec.getInputBuffer(index)); buffer.clear()
                                require(data.size <= buffer.remaining()) { "Audio packet too large" }
                                buffer.put(data)
                                dec.queueInputBuffer(index, 0, data.size, packet.timeUs, 0)
                            }
                            advanced = true
                        }
                    }
                    if (!decoderDone) {
                        val result = decode(dec, enc)
                        if (result != 0) advanced = true
                        if (result < 0) decoderDone = true
                    }
                    if (decoderDone) {
                        var index: Int
                        while (true) { check(); index = enc.dequeueInputBuffer(5_000); if (index >= 0) break; drain(enc, 0) }
                        enc.queueInputBuffer(index, 0, 0, time(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        while (!drain(enc, 10_000)) check()
                        return
                    }
                    if (drain(enc, 0)) return
                    if (advanced) idleSince = SystemClock.elapsedRealtime()
                    else require(SystemClock.elapsedRealtime() - idleSince < 30_000) { "The audio codec stopped responding" }
                }
            } finally { reading = false; readerThread.interrupt() }
        }

        private fun readPackets(track: CastMkvReader.Track) {
            try {
                val from = hls.segments[(start - 1).coerceAtLeast(0)].cluster
                val startNs = pcmStart * 1_000_000_000L / rate - PREROLL_NS
                reader.blocks(from, setOf(track.number)) { b ->
                    if (b.timeNs >= startNs) for (f in b.frames) {
                        while (reading && !queue.offer(Packet(b.timeNs / 1000, f), 50, TimeUnit.MILLISECONDS)) Unit
                    }
                    reading
                }
            } catch (_: Exception) {
            } finally {
                while (reading && !queue.offer(Packet(0, null), 50, TimeUnit.MILLISECONDS)) Unit
            }
        }

        private fun time() = (pcmStart + pcmWritten) * 1_000_000L / rate

        /** One decoder output step: 1 = progressed, -1 = end of stream, 0 = nothing yet. */
        private fun decode(dec: MediaCodec, enc: MediaCodec): Int {
            val index = dec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                channels = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                require(dec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == rate) { "The audio sample rate changed during conversion" }
                require(channels in 1..8) { "Unsupported audio channel layout" }
                return 1
            }
            if (index < 0) return 0
            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            if (info.size > 0) {
                if (channels == 0) channels = dec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val pcm = requireNotNull(dec.getOutputBuffer(index)).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                pcm.position(info.offset); pcm.limit(info.offset + info.size)
                val shorts = pcm.asShortBuffer()
                val total = shorts.remaining() / channels
                // Sample-accurate trim of anything decoded before pcmStart (preroll, previous frames).
                val bufferStart = info.presentationTimeUs * rate / 1_000_000L
                val expected = pcmStart + pcmWritten
                val skip = (expected - bufferStart).coerceIn(0, total.toLong()).toInt()
                val frames = total - skip
                if (frames > 0) {
                    if (mixIn.size < total * channels) mixIn = ShortArray(total * channels)
                    if (mixOut.size < frames * 2) mixOut = ShortArray(frames * 2)
                    shorts.get(mixIn, 0, total * channels)
                    stereo(mixIn, skip * channels, mixOut, frames, channels)
                    encode(enc, frames)
                }
            }
            dec.releaseOutputBuffer(index, false)
            return if (eos) -1 else 1
        }

        private fun encode(enc: MediaCodec, frames: Int) {
            var offset = 0
            while (offset < frames) {
                check()
                val index = enc.dequeueInputBuffer(5_000)
                if (index < 0) { drain(enc, 0); continue }
                val input = requireNotNull(enc.getInputBuffer(index)); input.clear(); input.order(ByteOrder.LITTLE_ENDIAN)
                val count = minOf(frames - offset, input.remaining() / 4)
                input.asShortBuffer().put(mixOut, offset * 2, count * 2)
                enc.queueInputBuffer(index, 0, count * 4, time(), 0)
                pcmWritten += count; offset += count
                drain(enc, 0)
            }
        }

        /** Emits encoded frames onto the grid; returns true at end of stream. */
        private fun drain(enc: MediaCodec, timeoutUs: Long): Boolean {
            while (true) {
                val index = enc.dequeueOutputBuffer(info, timeoutUs)
                if (index < 0) return false
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val out = requireNotNull(enc.getOutputBuffer(index))
                    out.position(info.offset); out.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size); out.get(bytes)
                    frame(nextLabel++, bytes)
                }
                enc.releaseOutputBuffer(index, false)
                if (eos) return true
            }
        }
    }

    /** VLC transcodes to a growing MPEG-TS; frames are read while it is still being written. */
    private inner class VlcProducer : AudioProducer(0) {
        private val aborted = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "VLCPlus-HLS-vlc").apply { isDaemon = true } }
        override fun stopWork() { aborted.set(true); executor.shutdownNow() }

        override fun produce() {
            val input = requireNotNull(localFile)
            val ts = File(directory, "vlc-audio-${System.nanoTime()}.ts").apply { createNewFile() }
            try {
                val writer = executor.submit<File> {
                    CastNativePreparation.convert(context, input, ts, true, { check(); if (aborted.get()) throw InterruptedException() }, {}, true)
                }
                var n = vlcStartFrame
                val parser = CastTsAac { data, _, _ -> frame(n++, data) }
                CastGrowingFile(ts, writer, aborted, Runnable { check() }).use { file ->
                    val buffer = ByteArray(64 * 1024)
                    var at = 0L
                    while (true) {
                        val count = file.readAt(at, buffer, 0, buffer.size)
                        if (count < 0) break
                        parser.feed(buffer, 0, count); at += count
                    }
                }
                parser.finish()
            } finally { ts.delete() }
        }
    }

    // ---- codec support ---------------------------------------------------------------------

    private fun stereo(input: ShortArray, offset: Int, output: ShortArray, frames: Int, channels: Int) {
        when (channels) {
            1 -> for (i in 0 until frames) { val v = input[offset + i]; output[2 * i] = v; output[2 * i + 1] = v }
            2 -> System.arraycopy(input, offset, output, 0, frames * 2)
            6, 8 -> CastDownmix.toStereo(if (offset == 0) input else input.copyOfRange(offset, offset + frames * channels), output, frames, channels)
            else -> for (i in 0 until frames) { output[2 * i] = input[offset + i * channels]; output[2 * i + 1] = input[offset + i * channels + 1] }
        }
    }

    private fun longBytes(v: Long) = ByteBuffer.allocate(8).putLong(v).array()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        producer?.stop()
        prefetch.shutdownNow()
        synchronized(audioLock) { audioLock.notifyAll() }
        synchronized(readerLock) { runCatching { videoReader.close() } }
        videoFiles.values.forEach { it.delete() }; audioFiles.values.forEach { it.delete() }
    }

    companion object {
        const val MIME = "application/x-mpegURL" // same as CastConnection.HLS_MIME
        private const val PLAYLIST = "application/vnd.apple.mpegurl"
        private const val PERF = "VLCPlus/CastPerf"
        private const val KEEP_BEHIND = 3
        private const val KEEP_AHEAD = 12
        private const val RESTART_DISTANCE = 4
        private const val AUDIO_WAIT_MS = 20_000L
        private const val ENCODER_DELAY = 2048
        private const val PREROLL_NS = 200_000_000L

        /** A Matroska movie Android can stream with copied video; null means "use full conversion". */
        fun open(context: Context, uri: Uri, directory: File): CastHlsSession? = try {
            val local = CastMovieLocation.localFile(context, uri)
            val path = local?.path?.takeIf { local.scheme == "file" || local.scheme == null }?.let(::File)
            val opener: () -> CastMkvReader = if (path != null) {
                { FileInputStream(path).let { CastMkvReader(CastMkvReader.ChannelSource(it.channel, it)) } }
            } else {
                require(uri.scheme == "content") { "Only local videos can be streamed" }
                val contentOpener: () -> CastMkvReader = {
                    val fd = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) { "The video cannot be opened" }
                    CastMkvReader(CastMkvReader.ChannelSource(FileInputStream(fd.fileDescriptor).channel, fd))
                }
                contentOpener
            }
            val size = path?.length() ?: context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            CastHlsSession(context.applicationContext, opener, path, directory, size)
        } catch (e: Exception) {
            Log.i(PERF, "path=hls-unavailable reason=${e.message}")
            null
        }

        private fun decoderFormat(track: CastMkvReader.Track): MediaFormat? {
            val mime = when {
                track.codecId == "A_AC3" -> "audio/ac3"
                track.codecId == "A_EAC3" -> "audio/eac3"
                track.codecId.startsWith("A_DTS") -> "audio/vnd.dts"
                track.codecId == "A_MPEG/L3" -> "audio/mpeg"
                track.codecId == "A_FLAC" -> "audio/flac"
                track.codecId.startsWith("A_AAC") -> "audio/mp4a-latm"
                else -> return null
            }
            val format = MediaFormat.createAudioFormat(mime, track.sampleRate.toInt(), track.channels.coerceAtLeast(1))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            when (mime) {
                "audio/mp4a-latm" -> format.setByteBuffer("csd-0", ByteBuffer.wrap(track.codecPrivate ?: return null))
                "audio/flac" -> format.setByteBuffer("csd-0", ByteBuffer.wrap(track.codecPrivate ?: return null))
            }
            return format.takeIf { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(it) != null }
        }

        private fun codecOutputRate(track: CastMkvReader.Track): Int {
            val rate = track.sampleRate.toInt()
            require(rate in intArrayOf(48000, 44100, 32000)) { "This audio sample rate needs full conversion" }
            return rate
        }
    }
}
