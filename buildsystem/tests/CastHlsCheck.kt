package org.videolan.vlc.cast

import java.io.File
import java.io.FileInputStream

/** Writes the complete HLS rendition of an MKV (copied AAC) so ffmpeg/ffprobe can verify it. */
fun main(args: Array<String>) {
    val input = File(args[0]); val out = File(args[1]).apply { deleteRecursively(); mkdirs() }
    val stream = FileInputStream(input)
    CastMkvReader(CastMkvReader.ChannelSource(stream.channel, stream)).use { reader ->
        val started = System.nanoTime()
        val hls = CastMkvHls(reader, requireNotNull(CastMkvHls.pickVideo(reader)), CastMkvHls.pickAudio(reader))
        val planned = System.nanoTime()
        File(out, "master.m3u8").writeText(hls.masterPlaylist(input.length()))
        File(out, "video.m3u8").writeText(hls.mediaPlaylist(false))
        File(out, "video-init.mp4").writeBytes(CastFmp4.init(hls.videoConfig))
        // Optional third argument: a TS file standing in for VLC's transcoded AAC stream.
        val transcoded = HashMap<Long, ByteArray>()
        if (args.size > 2) {
            var rate = 0
            val ts = CastTsAac { frame, r, _ -> rate = r; transcoded[transcoded.size.toLong()] = frame }
            File(args[2]).inputStream().use { inp -> val buf = ByteArray(65536); while (true) { val n = inp.read(buf); if (n < 0) break; ts.feed(buf, 0, n) } }
            ts.finish()
            hls.useTranscodedAudio(rate)
            println("transcodedFrames=${transcoded.size} rate=$rate")
        }
        if (hls.audio != null) {
            check(hls.audioCopy || transcoded.isNotEmpty()) { "fixture audio should be copyable AAC" }
            File(out, "audio.m3u8").writeText(hls.mediaPlaylist(true))
            File(out, "audio-init.mp4").writeBytes(CastFmp4.init(requireNotNull(hls.audioConfig)))
        }
        var firstSegmentMs = 0.0
        for (i in hls.segments.indices) {
            val t = System.nanoTime()
            File(out, "v$i.m4s").outputStream().use { hls.writeVideo(i, it) }
            if (hls.audio != null) File(out, "a$i.m4s").outputStream().use {
                if (transcoded.isEmpty()) hls.writeAudioCopy(i, it) else hls.writeAudio(i, it) { n -> transcoded[n] }
            }
            if (i == 0) firstSegmentMs = (System.nanoTime() - t) / 1e6
        }
        println("segments=${hls.segments.size} codec=${hls.videoCodec} planMs=${(planned - started) / 1_000_000} firstSegmentMs=${"%.1f".format(firstSegmentMs)}")
    }
}
