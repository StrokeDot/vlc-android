/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.annotation.TargetApi
import android.content.Context
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** Encode audio and copy original video concurrently into the final seekable MP4. */
@TargetApi(23)
internal object CastPipelinedPreparation {
    fun prepare(context: Context, input: File, directory: File, check: () -> Unit,
                progress: (String) -> Unit, fastAudio: Boolean = true, platformEncoder: Boolean = false): CastPreparation.Prepared {
        require(!platformEncoder || android.os.Build.VERSION.SDK_INT >= 28)
        val stream = File(directory, if (platformEncoder) "audio-pcm.mkv" else "audio-stream.ts").apply { check(createNewFile()) }
        val aborted = AtomicBoolean(false)
        val closed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "VLCPlus-AAC").apply { isDaemon = true } }
        val started = SystemClock.elapsedRealtime()
        val writer = executor.submit<File> {
            try {
                CastNativePreparation.convert(context, input, stream, true, {
                    check(); if (aborted.get()) throw java.util.concurrent.CancellationException("Preparation stopped")
                }, {}, fastAudio, pcm = platformEncoder)
            } finally { closed.countDown() }
        }
        var source: GrowingSource? = null
        var audio: MediaExtractor? = null
        try {
            progress("Preparing audio and video… 0%")
            val data = GrowingSource(stream, writer, aborted, check); source = data
            val extractor = MediaExtractor(); audio = extractor
            extractor.setDataSource(data)
            Log.i("VLCPlus/CastPerf", "path=${if (platformEncoder) "platform-aac" else "pipelined"} muxStartMs=${SystemClock.elapsedRealtime() - started} encoderComplete=${writer.isDone}")
            val prepared = if (platformEncoder) {
                CastPreparation.prepare(context, android.net.Uri.fromFile(input), directory, true, check, progress, extractor)
            } else {
                CastPreparation.withAudioStream(input, extractor, directory, check, progress)
            }
            writer.get(); check()
            return prepared
        } finally {
            // Do not Future.cancel(): a queued task must execute its finally/latch.
            aborted.set(true); executor.shutdown()
            var interrupted = false
            while (true) {
                try { closed.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            runCatching { audio?.release() }; runCatching { source?.close() }
            stream.delete()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** Unknown-length audio source: only completed encoding may report EOF to the extractor. */
    private class GrowingSource(file: File, private val writer: Future<File>,
                                private val aborted: AtomicBoolean, private val check: () -> Unit) : MediaDataSource() {
        private val reader = CastGrowingFile(file, writer, aborted, Runnable { check() })
        override fun getSize() = -1L
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = reader.readAt(position, buffer, offset, size)
        override fun close() = reader.close()

    }
}
