/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.annotation.TargetApi
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Prepare a finished, seekable MP4 when needed. Video is always sample-copied. */
@TargetApi(21)
internal object CastPreparation {
    data class Prepared(val file: File, val mime: String, val cacheSlot: File? = null)
    class AudioUnsupported(val input: File, cause: Throwable? = null) : Exception("Android could not read or convert this audio", cause)

    fun prepare(context: Context, uri: Uri, directory: File, convertAudio: Boolean,
                check: () -> Unit, progress: (String) -> Unit, pcmAudio: MediaExtractor? = null): Prepared {
        val local = CastMovieLocation.localFile(context, uri) ?: uri
        val input = when (local.scheme) {
            "file", null -> File(requireNotNull(local.path))
            "content" -> File(directory, "source").also { file ->
                val started = SystemClock.elapsedRealtime()
                val size = runCatching {
                    context.contentResolver.query(local, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
                    }
                }.getOrNull()
                progress("Reading video from storage…")
                var copied = 0L
                var lastProgress = started
                context.contentResolver.openInputStream(local)?.use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            check()
                            val n = source.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastProgress >= 1000) {
                                val detail = if (size != null) "${(copied * 100 / size).coerceIn(0, 99)}%"
                                    else "${copied / (1024 * 1024)} MiB"
                                progress("Reading video from storage… $detail")
                                lastProgress = now
                            }
                        }
                    }
                } ?: error("The video cannot be opened")
                Log.i("VLCPlus/CastPerf", "stage=source-staging bytes=$copied elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
            else -> error("Direct casting needs a local video. Use VLC casting for this network stream.")
        }
        require(input.isFile && input.length() > 0) { "The video file is unavailable" }
        check()
        val metadata = MediaMetadataRetriever()
        val mime: String
        val rotation: Int
        try {
            metadata.setDataSource(input.path)
            mime = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } finally { metadata.release() }
        if (!convertAudio && mime in listOf("video/mp4", "video/webm")) return Prepared(input, mime)
        require(directory.usableSpace > input.length() + 64L * 1024 * 1024) { "Not enough temporary storage to prepare this video" }
        val output = File(directory, if (pcmAudio == null) "prepared.mp4" else "platform-aac.mp4")
        val probe = MediaExtractor()
        try {
            probe.setDataSource(input.path)
            val video = (0 until probe.trackCount).firstOrNull { probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("No video track found")
            val audioSource = pcmAudio ?: probe
            val audio = (0 until audioSource.trackCount).firstOrNull { audioSource.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (convertAudio && audio == null) throw AudioUnsupported(input)
            val videoMime = probe.getTrackFormat(video).getString(MediaFormat.KEY_MIME)
            require(videoMime in listOf("video/avc", "video/hevc", "video/mp4v-es")) { "This video format needs VLC casting" }
            val audioMime = audio?.let { audioSource.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            if (audio != null && (convertAudio || audioMime != "audio/mp4a-latm")) {
                progress("Preparing audio for TV… 0%")
                try { convert(input, output, video, audio, rotation, check, progress, pcmAudio) }
                catch (e: java.util.concurrent.CancellationException) { throw e }
                catch (e: Exception) { throw AudioUnsupported(input, e) }
            } else {
                progress("Preparing video for TV… 0%")
                remux(probe, output, video, audio, rotation, check, progress)
            }
        } finally { probe.release() }
        check()
        return Prepared(output, "video/mp4")
    }

    /** Attach finished AAC; the caller only supplies a file, this method owns its extractor. */
    fun withAudio(input: File, compatibleAudio: File, directory: File, check: () -> Unit,
                  progress: (String) -> Unit): Prepared {
        val audio = MediaExtractor()
        try {
            audio.setDataSource(compatibleAudio.path)
            return muxAudio(input, audio, directory, "prepared.mp4", "Preparing video with compatible audio", false, check, progress)
        } finally { audio.release() }
    }

    /** Caller owns the live audio extractor; video bytes/timestamps use the same validated copy path. */
    fun withAudioStream(input: File, audio: MediaExtractor, directory: File, check: () -> Unit,
                        progress: (String) -> Unit): Prepared =
        muxAudio(input, audio, directory, "pipelined.mp4", "Preparing audio and video", true, check, progress)

    private fun muxAudio(input: File, audio: MediaExtractor, directory: File, outputName: String,
                         label: String, normalizeAudio: Boolean, check: () -> Unit,
                         progress: (String) -> Unit): Prepared {
        val video = MediaExtractor()
        val output = File(directory, outputName)
        var muxer: MediaMuxer? = null; var started = false
        try {
            video.setDataSource(input.path)
            val videoTrack = (0 until video.trackCount).firstOrNull { video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("No video track found")
            val audioTrack = (0 until audio.trackCount).firstOrNull { audio.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
                ?: error("VLC could not produce compatible AAC audio")
            val videoFormat = video.getTrackFormat(videoTrack)
            require(videoFormat.getString(MediaFormat.KEY_MIME) in listOf("video/avc", "video/hevc", "video/mp4v-es")) { "This video format needs VLC casting" }
            val audioFormat = audio.getTrackFormat(audioTrack)
            val metadata = MediaMetadataRetriever()
            val rotation = try { metadata.setDataSource(input.path); metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0 }
                finally { metadata.release() }
            val mux = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); muxer = mux
            mux.setOrientationHint(rotation)
            val muxVideo = mux.addTrack(videoFormat); val muxAudio = mux.addTrack(audioFormat)
            video.selectTrack(videoTrack); audio.selectTrack(audioTrack)
            val audioOrigin = if (normalizeAudio) audio.sampleTime.coerceAtLeast(0) else 0L
            fun audioTime() = if (audio.sampleTime < 0) -1L else audio.sampleTime - audioOrigin
            val data = ByteBuffer.allocateDirect(maxOf(bufferSize(videoFormat), bufferSize(audioFormat)))
            val info = MediaCodec.BufferInfo()
            val duration = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            mux.start(); started = true
            var reported = -1
            while (video.sampleTime >= 0 || audio.sampleTime >= 0) {
                check(); data.clear()
                val audioTime = audioTime()
                val useVideo = video.sampleTime >= 0 && (audioTime < 0 || video.sampleTime <= audioTime)
                val source = if (useVideo) video else audio
                require(source.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Protected video cannot be prepared" }
                val size = source.readSampleData(data, 0)
                require(size >= 0) { "The media file ended before its declared samples" }
                // TS extractors can retain ADTS framing; MP4 requires the raw AAC payload.
                // Existing M4A audio and every original video sample remain unchanged.
                val offset = if (normalizeAudio && !useVideo) CastAacSample.headerLength(data, size) else 0
                info.set(offset, size - offset, (if (useVideo) video.sampleTime else audioTime).coerceAtLeast(0), source.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)
                mux.writeSampleData(if (useVideo) muxVideo else muxAudio, data, info)
                val percent = if (duration > 0) (info.presentationTimeUs * 100 / duration).toInt().coerceIn(0, 99) else 0
                if (percent != reported) { reported = percent; progress("$label… $percent%") }
                source.advance()
            }
        } finally {
            try { if (started) muxer?.stop() } finally { muxer?.release(); video.release() }
        }
        check()
        return Prepared(output, "video/mp4")
    }

    private fun bufferSize(format: MediaFormat): Int =
        (if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0)
            .coerceIn(4 * 1024 * 1024, 32 * 1024 * 1024)

    private fun remux(extractor: MediaExtractor, output: File, video: Int, audio: Int?, rotation: Int,
                      check: () -> Unit, progress: (String) -> Unit) {
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            muxer.setOrientationHint(rotation)
            val mapping = mutableMapOf(video to muxer.addTrack(extractor.getTrackFormat(video)))
            if (audio != null) mapping[audio] = muxer.addTrack(extractor.getTrackFormat(audio))
            mapping.keys.forEach { extractor.selectTrack(it) }
            val duration = extractor.getTrackFormat(video).let { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0L }
            val data = ByteBuffer.allocateDirect(mapping.keys.maxOf { bufferSize(extractor.getTrackFormat(it)) })
            val info = MediaCodec.BufferInfo()
            muxer.start(); started = true
            var reported = -1
            while (extractor.sampleTrackIndex >= 0) {
                check(); data.clear()
                require(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Protected video cannot be prepared" }
                val size = extractor.readSampleData(data, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime.coerceAtLeast(0), extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)
                muxer.writeSampleData(mapping.getValue(extractor.sampleTrackIndex), data, info)
                val percent = if (duration > 0) (info.presentationTimeUs * 100 / duration).toInt().coerceIn(0, 99) else 0
                if (percent != reported) { reported = percent; progress("Preparing video for TV… $percent%") }
                extractor.advance()
            }
        } finally {
            try { if (started) muxer.stop() } finally { muxer.release() }
        }
    }

    private fun convert(input: File, output: File, videoTrack: Int, audioTrack: Int, rotation: Int,
                        check: () -> Unit, progress: (String) -> Unit, pcmAudio: MediaExtractor?) {
        val audio = pcmAudio ?: MediaExtractor()
        val video = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxStarted = false
        try {
            if (pcmAudio == null) audio.setDataSource(input.path)
            audio.selectTrack(audioTrack)
            val audioOrigin = if (pcmAudio == null) 0L else audio.sampleTime.coerceAtLeast(0)
            video.setDataSource(input.path); video.selectTrack(videoTrack)
            val inputFormat = audio.getTrackFormat(audioTrack)
            fun startEncoder(sampleRate: Int, channels: Int) {
                val aac = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels.coerceAtMost(2))
                aac.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                aac.setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                aac.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
                encoder = codec
                codec.configure(aac, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
            }
            val dec = if (pcmAudio == null) {
                inputFormat.setInteger("pcm-encoding", AudioFormat.ENCODING_PCM_16BIT)
                MediaCodec.createDecoderByType(requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME))).also {
                    decoder = it
                    it.configure(inputFormat, null, null, 0)
                    it.start()
                }
            } else {
                require(inputFormat.getString(MediaFormat.KEY_MIME) == "audio/raw" &&
                    inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2 &&
                    inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48000 &&
                    (!inputFormat.containsKey("pcm-encoding") || inputFormat.getInteger("pcm-encoding") == AudioFormat.ENCODING_PCM_16BIT)) {
                    "The intermediate audio is not 48 kHz stereo PCM"
                }
                startEncoder(48000, 2)
                null
            }
            val mux = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux; mux.setOrientationHint(rotation)
            val videoFormat = video.getTrackFormat(videoTrack)
            val muxVideo = mux.addTrack(videoFormat)
            var muxAudio = -1
            val duration = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) videoFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            val videoData = ByteBuffer.allocateDirect(bufferSize(videoFormat))
            val videoInfo = MediaCodec.BufferInfo()
            fun copyVideoUntil(time: Long) {
                while (video.sampleTime >= 0 && video.sampleTime <= time) {
                    check(); videoData.clear()
                    require(video.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Protected video cannot be prepared" }
                    val size = video.readSampleData(videoData, 0)
                    require(size >= 0) { "The video ended before its declared samples" }
                    videoInfo.set(0, size, video.sampleTime.coerceAtLeast(0), video.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC)
                    mux.writeSampleData(muxVideo, videoData, videoInfo); video.advance()
                }
            }
            val decodedInfo = MediaCodec.BufferInfo()
            val encodedInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderEos = false
            var encoderDone = false
            var pendingIndex = -1
            var pcm: ByteBuffer? = null
            var pcmTime = 0L
            var pcmFrames = 0L
            var pendingEos = false
            var channels = 0
            var sampleRate = 0
            var reported = -1
            var lastProgressNs = System.nanoTime()
            var pcmEndTime = 0L
            while (!encoderDone) {
                check()
                var advanced = false
                if (!inputDone) {
                    val feeder = dec ?: requireNotNull(encoder)
                    val index = feeder.dequeueInputBuffer(if (dec == null) 1000 else 0)
                    if (index >= 0) {
                        val bytes = requireNotNull(feeder.getInputBuffer(index)); bytes.clear()
                        if (dec == null) {
                            val firstTime = audio.sampleTime
                            var size = 0
                            // PCM arrives in small packets. Batch adjacent packets without crossing a timestamp gap.
                            while (audio.sampleTime >= 0) {
                                val expectedTime = firstTime + size / 4L * 1_000_000L / 48000
                                if (size > 0 && kotlin.math.abs(audio.sampleTime - expectedTime) > 1000L) break
                                val packetSize = audio.sampleSize
                                require(packetSize in 4..bytes.capacity().toLong() && packetSize % 4L == 0L) { "Invalid stereo PCM packet" }
                                if (packetSize > bytes.capacity() - size) break
                                bytes.clear()
                                require(audio.readSampleData(bytes, size).toLong() == packetSize) { "Incomplete stereo PCM packet" }
                                size += packetSize.toInt()
                                audio.advance()
                            }
                            if (size == 0) {
                                feeder.queueInputBuffer(index, 0, 0, pcmEndTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                                encoderEos = true
                            } else {
                                val time = (firstTime - audioOrigin).coerceAtLeast(0)
                                feeder.queueInputBuffer(index, 0, size, time, 0)
                                pcmEndTime = time + size / 4L * 1_000_000L / 48000
                            }
                        } else {
                            val size = audio.readSampleData(bytes, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                require(audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Protected audio cannot be prepared" }
                                dec.queueInputBuffer(index, 0, size, audio.sampleTime.coerceAtLeast(0), 0)
                                audio.advance()
                            }
                        }
                        advanced = true
                    }
                }
                if (dec != null && pendingIndex < 0 && !decoderDone) {
                    val index = dec.dequeueOutputBuffer(decodedInfo, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        require(encoder == null) { "The audio format changed during preparation" }
                        val format = dec.outputFormat
                        require(!format.containsKey("pcm-encoding") || format.getInteger("pcm-encoding") == AudioFormat.ENCODING_PCM_16BIT) { "This audio decoder does not provide supported PCM" }
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        // Avoid silently discarding channels in uncommon PCM layouts.
                        // These are the standard mono/stereo/5.1/7.1 layouts below.
                        require(channels in listOf(1, 2, 6, 8) && sampleRate > 0) { "This audio layout needs VLC casting" }
                        if (channels > 2 && format.containsKey(MediaFormat.KEY_CHANNEL_MASK)) {
                            val mask = format.getInteger(MediaFormat.KEY_CHANNEL_MASK)
                            val expected = if (channels == 6) AudioFormat.CHANNEL_OUT_5POINT1 else AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                            require(mask == 0 || mask == expected) { "This audio channel layout needs VLC casting" }
                        }
                        startEncoder(sampleRate, channels)
                        advanced = true
                    } else if (index >= 0) {
                        pendingEos = decodedInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (decodedInfo.size > 0) {
                            pendingIndex = index
                            pcm = requireNotNull(dec.getOutputBuffer(index)).duplicate().apply {
                                position(decodedInfo.offset); limit(decodedInfo.offset + decodedInfo.size); order(ByteOrder.LITTLE_ENDIAN)
                            }
                            pcmTime = decodedInfo.presentationTimeUs; pcmFrames = 0
                        } else { dec.releaseOutputBuffer(index, false); if (pendingEos) decoderDone = true }
                        advanced = true
                    }
                }
                val enc = encoder
                val source = pcm
                if (dec != null && pendingIndex >= 0 && enc != null && source != null) {
                    val index = enc.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val bytes = requireNotNull(enc.getInputBuffer(index)); bytes.clear(); bytes.order(ByteOrder.LITTLE_ENDIAN)
                        val outChannels = channels.coerceAtMost(2)
                        val frames = minOf(source.remaining() / (channels * 2), bytes.remaining() / (outChannels * 2))
                        require(frames > 0) { "Invalid PCM audio buffer" }
                        if (channels <= 2) {
                            val limit = source.limit()
                            source.limit(source.position() + frames * channels * 2)
                            bytes.put(source)
                            source.limit(limit)
                        } else {
                            for (frame in 0 until frames) {
                                var left = 0.0; var right = 0.0
                                for (channel in 0 until channels) {
                                    val value = source.short.toInt()
                                    when (channel) {
                                        0 -> left += value; 1 -> right += value
                                        2 -> { left += value * 0.707; right += value * 0.707 }
                                        4 -> left += value * 0.707; 5 -> right += value * 0.707
                                        6 -> left += value * 0.5; 7 -> right += value * 0.5
                                    }
                                }
                                bytes.putShort((left / 2.5).toInt().coerceIn(-32768, 32767).toShort())
                                bytes.putShort((right / 2.5).toInt().coerceIn(-32768, 32767).toShort())
                            }
                        }
                        enc.queueInputBuffer(index, 0, bytes.position(), pcmTime + pcmFrames * 1000000L / sampleRate, 0)
                        pcmFrames += frames
                        if (!source.hasRemaining()) { dec.releaseOutputBuffer(pendingIndex, false); pendingIndex = -1; pcm = null; if (pendingEos) decoderDone = true }
                        advanced = true
                    }
                }
                if (decoderDone && pendingIndex < 0 && !encoderEos && enc != null) {
                    val index = enc.dequeueInputBuffer(0)
                    if (index >= 0) { enc.queueInputBuffer(index, 0, 0, pcmTime + pcmFrames * 1000000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM); encoderEos = true; advanced = true }
                }
                if (enc != null) {
                    var index = enc.dequeueOutputBuffer(encodedInfo, if (dec == null && inputDone) 1000 else 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxAudio = mux.addTrack(enc.outputFormat); mux.start(); muxStarted = true; advanced = true
                        index = enc.dequeueOutputBuffer(encodedInfo, 0)
                    }
                    while (index >= 0) {
                        if (encodedInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encodedInfo.size > 0) {
                            kotlin.check(muxStarted) { "AAC format was not available" }
                            copyVideoUntil(encodedInfo.presentationTimeUs)
                            val bytes = requireNotNull(enc.getOutputBuffer(index))
                            bytes.position(encodedInfo.offset); bytes.limit(encodedInfo.offset + encodedInfo.size)
                            mux.writeSampleData(muxAudio, bytes, encodedInfo)
                            val percent = if (duration > 0) (encodedInfo.presentationTimeUs * 100 / duration).toInt().coerceIn(0, 99) else 0
                            if (percent != reported) { reported = percent; progress("Preparing audio for TV… $percent%") }
                        }
                        encoderDone = encodedInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        enc.releaseOutputBuffer(index, false); advanced = true
                        index = enc.dequeueOutputBuffer(encodedInfo, 0)
                    }
                }
                if (advanced) lastProgressNs = System.nanoTime()
                else {
                    require(System.nanoTime() - lastProgressNs < 30_000_000_000L) { "The audio codec stopped responding" }
                    if (dec != null) Thread.sleep(2)
                }
            }
            require(muxStarted) { "No audio could be converted" }
            copyVideoUntil(Long.MAX_VALUE)
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            try { if (muxStarted) muxer?.stop() } finally { muxer?.release(); if (pcmAudio == null) audio.release(); video.release() }
        }
    }
}
