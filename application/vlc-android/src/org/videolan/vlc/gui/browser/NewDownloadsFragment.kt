package org.videolan.vlc.gui.browser

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.MediaStore.Video.Media
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.ListFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.resources.util.getFromMl
import org.videolan.vlc.R
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.media.MediaUtils
import java.io.File

class NewDownloadsFragment : ListFragment() {
    private data class Download(val name: String, val media: MediaWrapper, val played: Boolean)

    private var loadJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setEmptyText(getString(R.string.new_downloads_empty))
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as SecondaryActivity).supportActionBar?.setTitle(R.string.new_downloads)
        val context = requireContext()
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val downloads = loadDownloads(context)
            val list = listView
            val first = list.firstVisiblePosition
            val top = list.getChildAt(0)?.top ?: 0
            listAdapter = object : ArrayAdapter<Download>(context, android.R.layout.simple_list_item_2, downloads) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView ?: LayoutInflater.from(context)
                        .inflate(android.R.layout.simple_list_item_2, parent, false)
                    val download = getItem(position)!!
                    row.findViewById<TextView>(android.R.id.text1).text = download.name
                    row.findViewById<TextView>(android.R.id.text2).setText(
                        if (download.played) R.string.new_downloads_played else R.string.new_downloads_unplayed
                    )
                    return row
                }
            }
            list.setSelectionFromTop(first, top)
        }
    }

    override fun onListItemClick(list: ListView, view: View, position: Int, id: Long) {
        val download = list.getItemAtPosition(position) as Download
        MediaUtils.openMedia(requireContext(), download.media)
    }

    @Suppress("DEPRECATION")
    private suspend fun loadDownloads(context: Context): List<Download> = context.getFromMl {
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

        val downloads = mutableListOf<Download>()
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
                    downloads.add(Download(name, media, played))
                }
            }
        } catch (_: SecurityException) {
            // The system may revoke video access while the list is loading.
        }
        downloads
    }
}
