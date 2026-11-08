package org.videolan.vlc.providers

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Video.Media
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.util.getFromMl
import org.videolan.vlc.R
import java.io.File

internal const val NEW_DOWNLOADS_LOCATION = "vlcplus://new-downloads"

@Suppress("DEPRECATION")
internal suspend fun loadNewDownloads(context: Context): MutableList<MediaLibraryItem> = context.getFromMl {
    val columns = mutableListOf(Media._ID, Media.DISPLAY_NAME, Media.DATA)
    val downloadDirectory = Environment.DIRECTORY_DOWNLOADS
    val scopedStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val pathColumn: String
    val prefix: String
    if (scopedStorage) {
        columns.add(MediaStore.MediaColumns.VOLUME_NAME)
        pathColumn = MediaStore.MediaColumns.RELATIVE_PATH
        prefix = ""
    } else {
        pathColumn = Media.DATA
        prefix = "%/"
    }

    val downloads = mutableListOf<MediaLibraryItem>()
    try {
        context.contentResolver.query(
            Media.EXTERNAL_CONTENT_URI,
            columns.toTypedArray(),
            "$pathColumn LIKE ? OR $pathColumn LIKE ?",
            arrayOf("${prefix}${downloadDirectory}/%", "${prefix}Downloads/%"),
            "${Media.DATE_ADDED} DESC, ${Media._ID} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: continue
                val file = cursor.getString(2)?.let(::File)
                val fileUri = file?.let(Uri::fromFile)
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    Media.getContentUri(cursor.getString(3)) else Media.EXTERNAL_CONTENT_URI
                val contentUri = ContentUris.withAppendedId(collection, id)
                val knownMedia = fileUri?.let { getMedia(it) } ?: getMedia(contentUri)
                val played = knownMedia != null &&
                    (knownMedia.playCount > 0 || knownMedia.time > 0 || knownMedia.displayTime > 0)
                val media = knownMedia ?: MLServiceLocator.getAbstractMediaWrapper(
                    if (file?.canRead() == true) fileUri!! else contentUri
                ).apply {
                    type = MediaWrapper.TYPE_VIDEO
                    title = name
                }
                media.description = context.getString(
                if (played) R.string.new_downloads_played else R.string.new_downloads_unplayed
            )
            downloads.add(media)
            }
        }
    } catch (_: SecurityException) {
        // The system may revoke video access while the list is loading.
    }
    downloads
}
