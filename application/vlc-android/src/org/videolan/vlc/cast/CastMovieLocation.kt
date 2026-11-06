/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** Path-only lookup: never opens or transfers a playback file descriptor. */
internal object CastMovieLocation {
    fun localFile(context: Context, uri: Uri): Uri? {
        if (uri.scheme == "file" || uri.scheme == null) return uri
        if (uri.scheme != "content") return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use {
                if (!it.moveToFirst()) return@use null
                val path = it.getString(0) ?: return@use null
                File(path).takeIf { file -> file.isFile && file.canRead() }?.let(Uri::fromFile)
            }
        }.getOrNull()
    }

    fun initialFolder(uri: Uri): Uri? {
        val authority = "com.android.externalstorage.documents"
        if (uri.scheme == "content" && uri.authority == authority) {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            if (!id.contains(':')) return null
            val volume = id.substringBefore(':')
            val relative = id.substringAfter(':')
            val parent = relative.substringBeforeLast('/', "")
            val parentId = "$volume:$parent"
            return if (DocumentsContract.isTreeUri(uri)) DocumentsContract.buildDocumentUriUsingTree(uri, parentId)
                else DocumentsContract.buildDocumentUri(authority, parentId)
        }
        val path = uri.path ?: return null
        if (uri.scheme != null && uri.scheme != "file") return null
        val parent = path.substringBeforeLast('/', "")
        val id = when {
            parent == "/storage/emulated/0" -> "primary:"
            parent.startsWith("/storage/emulated/0/") -> "primary:" + parent.removePrefix("/storage/emulated/0/")
            parent.startsWith("/storage/") -> parent.removePrefix("/storage/").let { it.substringBefore('/') + ":" + it.substringAfter('/', "") }
            else -> return null
        }
        return DocumentsContract.buildDocumentUri(authority, id)
    }
}
