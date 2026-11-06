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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Never silently drop a Dolby/DTS track omitted by Android's container reader. */
internal object CastAudioPreparation {
    fun prepare(context: Context, uri: Uri, directory: File, check: () -> Unit,
                progress: (String) -> Unit): CastPreparation.Prepared = try {
        CastPreparation.prepare(context, uri, directory, true, check, progress)
    } catch (unsupported: CastPreparation.AudioUnsupported) {
        prepareWithVlc(context, unsupported.input, directory, check, progress)
    }

    private fun prepareWithVlc(context: Context, input: File, directory: File, check: () -> Unit,
                               progress: (String) -> Unit): CastPreparation.Prepared {
        progress("Converting audio with VLC…")
        val output = File(directory, "compatible-audio.mp4")
        val engine = LibVLC(context, arrayListOf("--no-video-title-show", "--quiet"))
        var media: Media? = null
        var player: MediaPlayer? = null
        try {
            val source = Media(engine, Uri.fromFile(input)); media = source
            check(); require(source.parse()) { "VLC could not read this file" }
            require((0 until source.trackCount).any { source.getTrack(it)?.type == IMedia.Track.Type.Audio }) { "No audio track could be found in this video" }
            val duration = source.duration
            // Native VLC reads codecs omitted by MediaExtractor. MP3 stereo is
            // Cast-compatible; unspecified video codec means sample passthrough.
            source.addOption(":sout=#transcode{acodec=mp3,ab=192,channels=2}:standard{access=file,mux=mp4,dst='${output.path}'}")
            source.addOption(":sout-mux-caching=0")
            val done = CountDownLatch(1)
            val failed = AtomicBoolean(false)
            val position = AtomicLong(0)
            val converter = MediaPlayer(engine); player = converter
            converter.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.TimeChanged -> position.set(event.timeChanged)
                    MediaPlayer.Event.EndReached -> done.countDown()
                    MediaPlayer.Event.EncounteredError -> { failed.set(true); done.countDown() }
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
                if (percent != reported) { reported = percent; progress("Converting audio with VLC… $percent%") }
                val size = output.length()
                if (size != previousSize) { previousSize = size; advancedAt = SystemClock.elapsedRealtime() }
                require(SystemClock.elapsedRealtime() - advancedAt < 60000) { "VLC audio conversion stopped responding" }
            }
            check()
            require(!failed.get()) { "VLC could not convert this audio" }
            converter.stop()
            require(output.isFile && output.length() > 0) { "No converted audio file was produced" }
            return CastPreparation.Prepared(output, "video/mp4")
        } finally {
            runCatching { player?.setEventListener(null) }; runCatching { player?.stop() }
            runCatching { player?.release() }; runCatching { media?.release() }; engine.release()
        }
    }
}
