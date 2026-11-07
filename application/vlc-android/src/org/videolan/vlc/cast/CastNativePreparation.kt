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

/** Decode and downmix audio for native AAC or a timed PCM handoff to Android. */
internal object CastNativePreparation {
    fun convert(context: Context, input: File, output: File, streaming: Boolean,
                check: () -> Unit, progress: (String) -> Unit, fastAudio: Boolean = streaming, pcm: Boolean = false): File {
        val label = "Converting audio with VLC"
        progress("$label…")
        val engine = LibVLC(context, arrayListOf("--no-video-title-show", "--verbose=0"))
        var media: Media? = null
        var player: MediaPlayer? = null
        try {
            val source = Media(engine, Uri.fromFile(input)); media = source
            check(); require(source.parse()) { "VLC could not read this file" }
            require((0 until source.trackCount).any { source.getTrack(it)?.type == IMedia.Track.Type.Audio }) { "No audio track could be found in this video" }
            val duration = source.duration
            // Wait for Stopped/INPUT_EVENT_DEAD: EndReached is emitted before
            // native stream output teardown, where buffered packets are finalized.
            val pcmBudget = if (pcm) output.parentFile!!.usableSpace - input.length() - 64L * 1024 * 1024 else Long.MAX_VALUE
            if (pcm) require(duration > 0 && duration < pcmBudget / 192L) { "Not enough temporary space for fast audio preparation" }
            val mux = when {
                pcm -> "avformat{mux=matroska,options={live=1,cluster_time_limit=250,flush_packets=1}}"
                streaming -> "ts"
                else -> "mp4"
            }
            val audio = if (pcm) "acodec=s16l,channels=2,samplerate=48000" else {
                val resample = if (streaming) ",samplerate=48000" else ""
                val encoder = if (fastAudio) ",aenc=avcodec{options={aac_coder=fast}}" else ""
                "acodec=mp4a$encoder,ab=192,channels=2$resample"
            }
            source.addOption(":sout=#transcode{$audio}:standard{access=file,mux=$mux,dst='${output.path}'}")
            source.addOption(":sout-mux-caching=0")
            source.addOption(":no-sout-video")
            source.addOption(":no-sout-spu")
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
                require(size <= pcmBudget) { "Fast audio preparation exceeded its temporary space budget" }
                if (size != previousSize) { previousSize = size; advancedAt = SystemClock.elapsedRealtime() }
                require(SystemClock.elapsedRealtime() - advancedAt < 60000) { "VLC audio conversion stopped responding" }
            }
            check()
            require(done.successful()) { "VLC could not convert this audio" }
            converter.stop()
            require(output.isFile && output.length() > 0) { "No converted audio file was produced" }
            require(output.length() <= pcmBudget) { "Fast audio preparation exceeded its temporary space budget" }
            return output
        } finally {
            runCatching { player?.setEventListener(null) }; runCatching { player?.stop() }
            runCatching { player?.release() }; runCatching { media?.release() }; engine.release()

        }
    }
}
