/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.util.Locale

/** Open seekable originals without copying or remuxing the entire movie first. */
internal object CastInput {
    data class Original(val source: CastFileServer.Source, val mime: String)

    fun open(context: Context, uri: Uri): Original {
        var name = uri.lastPathSegment ?: ""
        var providerMime: String? = null
        val source: CastFileServer.Source = when (uri.scheme) {
            "file", null -> CastFileServer.FileSource(File(requireNotNull(uri.path)))
            "content" -> {
                providerMime = context.contentResolver.getType(uri)
                runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0) ?: name
                } }
                val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
                    asset.createInputStream().use { input ->
                        val size = if (asset.declaredLength >= 0) asset.declaredLength else input.channel.size() - asset.startOffset
                        require(size > 0) { "The file length is unavailable. Choose a local video or Convert." }
                        input.channel.position(asset.startOffset + size - 1)
                        size
                    }
                } ?: error("The video cannot be opened")
                object : CastFileServer.Source {
                    override fun length() = length
                    override fun open(offset: Long): InputStream {
                        val asset = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: error("The video cannot be opened")
                        try {
                            val input = asset.createInputStream()
                            try { input.channel.position(asset.startOffset + offset) }
                            catch (e: Exception) { input.close(); throw e }
                            return object : InputStream() {
                                override fun read() = input.read()
                                override fun read(bytes: ByteArray, start: Int, count: Int) = input.read(bytes, start, count)
                                override fun close() { try { input.close() } finally { runCatching { asset.close() } } }
                            }
                        } catch (e: Exception) { runCatching { asset.close() }; throw e }
                    }
                }
            }
            else -> error("Choose a local video or use VLC casting for this network stream.")
        }
        val mime = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mp4", "m4v", "mov" -> "video/mp4"
            "ts", "m2ts" -> "video/mp2t"
            else -> providerMime?.takeIf { it.startsWith("video/") } ?: "video/mp4"
        }
        return Original(source, mime)
    }
}
