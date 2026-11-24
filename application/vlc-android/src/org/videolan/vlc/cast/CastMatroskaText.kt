/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream

/** Opens local movies (paths or content URIs) for the Android-independent Matroska readers. */
internal object CastMatroskaText {
    fun <T> open(context: Context, uri: Uri, action: (CastMkvReader) -> T): T {
        val local = CastMovieLocation.localFile(context, uri) ?: uri
        val source = if (local.scheme == "file" || local.scheme == null) {
            val stream = FileInputStream(File(requireNotNull(local.path)))
            CastMkvReader.ChannelSource(stream.channel, stream)
        } else {
            require(local.scheme == "content") { "Only local video files are supported" }
            val descriptor = context.contentResolver.openFileDescriptor(local, "r")
                ?: error("The movie cannot be opened for embedded subtitle extraction")
            CastMkvReader.ChannelSource(FileInputStream(descriptor.fileDescriptor).channel, descriptor)
        }
        return CastMkvReader(source).use(action)
    }

    fun tracks(context: Context, uri: Uri): List<CastMkvSubtitles.Track> = open(context, uri) { CastMkvSubtitles.tracks(it) }

    fun extract(context: Context, uri: Uri, trackNumber: Int): String = open(context, uri) { CastMkvSubtitles.extract(it, trackNumber) }
}
