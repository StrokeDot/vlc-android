/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Native AAC encoding plus optional video passthrough in one finalized output. */
internal object CastNativePreparation {
    fun convert(context: Context, input: File, output: File, copyVideo: Boolean,
                check: () -> Unit, progress: (String) -> Unit): File {
        val label = if (copyVideo) "Preparing compatible video and audio" else "Converting audio with VLC"
        progress("$label…")
        val engine = LibVLC(context, arrayListOf("--no-video-title-show", "--quiet"))
        var media: Media? = null
        var player: MediaPlayer? = null
        try {
            val source = Media(engine, Uri.fromFile(input)); media = source
            check(); require(source.parse()) { "VLC could not read this file" }
            require((0 until source.trackCount).any { source.getTrack(it)?.type == IMedia.Track.Type.Audio }) { "No audio track could be found in this video" }
            val duration = source.duration
            // Wait for Stopped/INPUT_EVENT_DEAD: EndReached is emitted before
            // native stream output teardown, where buffered packets are finalized.
            source.addOption(":sout=#transcode{acodec=mp4a,ab=192,channels=2}:standard{access=file,mux=mp4,dst='${output.path}'}")
            source.addOption(":sout-mux-caching=0")
            if (!copyVideo) source.addOption(":no-sout-video")
            val done = CastConversionSignal()
            val position = AtomicLong(0)
            val converter = MediaPlayer(engine); player = converter
            converter.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.TimeChanged -> position.set(event.timeChanged)
                    MediaPlayer.Event.EndReached -> done.inputEnded()
                    MediaPlayer.Event.Stopped -> done.stopped()
                    MediaPlayer.Event.EncounteredError -> done.error()
                }
            }
            converter.media = source
            converter.play()
            var reported = -1
            var previousSize = 0L
            var advancedAt = SystemClock.elapsedRealtime()
            while (!done.await(100, TimeUnit.MILLISECONDS)) {
                check()
                val percent = if (duration > 0) (position.get() * 100 / duration).toInt().coerceIn(0, 99) else 0
                if (percent != reported) { reported = percent; progress("$label… $percent%") }
                val size = output.length()
                if (size != previousSize) { previousSize = size; advancedAt = SystemClock.elapsedRealtime() }
                require(SystemClock.elapsedRealtime() - advancedAt < 60000) { "VLC audio conversion stopped responding" }
            }
            check()
            require(done.successful()) { "VLC could not convert this audio" }
            converter.stop()
            require(output.isFile && output.length() > 0) { "No converted audio file was produced" }
            return output
        } finally {
            runCatching { player?.setEventListener(null) }; runCatching { player?.stop() }
            runCatching { player?.release() }; runCatching { media?.release() }; engine.release()

        }
    }
}
