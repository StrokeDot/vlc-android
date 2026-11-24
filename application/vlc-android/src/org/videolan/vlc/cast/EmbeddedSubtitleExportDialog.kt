/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.videolan.vlc.R

/** Saves embedded captions as a normal file, independently of any cast session. */
class EmbeddedSubtitleExportDialog : DialogFragment() {
    private val model by viewModels<EmbeddedSubtitleExportModel>()
    private var selectedTrack: Int? = null
    private val movie get() = Uri.parse(requireArguments().getString("movie")!!)
    private val movieTitle get() = requireArguments().getString("title").orEmpty()
    private val saveFile = registerForActivityResult(object : ActivityResultContracts.CreateDocument("text/vtt") {
        override fun createIntent(context: Context, input: String): Intent =
            super.createIntent(context, input).apply {
                if (Build.VERSION.SDK_INT >= 26) model.state.value?.folder?.let {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, it)
                }
            }
    }) { destination ->
        val track = selectedTrack
        selectedTrack = null
        if (destination != null && track != null) model.save(movie, track, destination)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.containsKey("track") == true)
            selectedTrack = savedInstanceState.getInt("track")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedTrack?.let { outState.putInt("track", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), 0)
        }
        val message = TextView(context)
        val progress = ProgressBar(context)
        val list = ListView(context).apply {
            divider = null
            setOnItemClickListener { _, _, position, _ ->
                if (selectedTrack != null) return@setOnItemClickListener
                val track = model.state.value?.tracks?.getOrNull(position) ?: return@setOnItemClickListener
                selectedTrack = track.index
                val stem = movieTitle.substringBeforeLast('.', movieTitle).ifBlank { "Subtitles" }
                    .replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "_")
                val language = track.language.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "und" }
                val number = if (track.index < 0) -track.index else track.index + 1
                try {
                    saveFile.launch("$stem.$language.$number.vtt")
                } catch (_: android.content.ActivityNotFoundException) {
                    selectedTrack = null
                    Toast.makeText(context, R.string.subtitle_export_no_picker, Toast.LENGTH_LONG).show()
                }
            }
        }
        content.addView(message)
        content.addView(progress)
        content.addView(list)
        model.state.observe(this) { state ->
            if (state.saved) {
                Toast.makeText(context, R.string.subtitle_export_saved, Toast.LENGTH_LONG).show()
                dismiss()
                return@observe
            }
            message.text = state.message
            progress.visibility = if (state.busy) View.VISIBLE else View.GONE
            list.visibility = if (!state.busy && state.tracks.isNotEmpty()) View.VISIBLE else View.GONE
            list.adapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, state.tracks.map { it.label })
            list.layoutParams = list.layoutParams.apply {
                height = (minOf(state.tracks.size * 56, 280) * density).toInt()
            }
        }
        model.inspect(movie)
        return AlertDialog.Builder(context).setTitle(R.string.extract_subtitles)
            .setView(content).setNegativeButton(android.R.string.cancel, null).create()
    }

    companion object {
        private const val TAG = "embedded-subtitle-export"

        fun show(activity: FragmentActivity, movie: Uri, title: String) {
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            EmbeddedSubtitleExportDialog().apply {
                arguments = bundleOf("movie" to movie.toString(), "title" to title)
            }.show(manager, TAG)
        }
    }
}

internal class EmbeddedSubtitleExportModel(application: Application) : AndroidViewModel(application) {
    data class State(
        val tracks: List<CastEmbeddedSubtitles.Track> = emptyList(),
        val message: String,
        val busy: Boolean = true,
        val saved: Boolean = false,
        val folder: Uri? = null
    )

    private val context = application.applicationContext
    val state = MutableLiveData(State(message = context.getString(R.string.subtitle_export_scanning)))
    private var job: Job? = null

    fun inspect(movie: Uri) {
        if (job != null) return
        job = viewModelScope.launch {
            try {
                val result = runInterruptible(Dispatchers.IO) {
                    val tracks = CastEmbeddedSubtitles.tracks(context, movie)
                    val local = CastMovieLocation.localFile(context, movie) ?: movie
                    State(tracks, context.getString(if (tracks.isEmpty()) R.string.subtitle_export_empty
                        else R.string.subtitle_export_choose), busy = false, folder = CastMovieLocation.initialFolder(local))
                }
                state.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = State(message = e.message ?: context.getString(R.string.subtitle_export_failed), busy = false)
            }
        }
    }

    fun save(movie: Uri, track: Int, destination: Uri) {
        job?.cancel()
        state.value = state.value!!.copy(busy = true, message = context.getString(R.string.subtitle_export_saving))
        job = viewModelScope.launch {
            var completed = false
            try {
                runInterruptible(Dispatchers.IO) {
                    val subtitles = CastEmbeddedSubtitles.extract(context, movie, track)
                    val output = context.contentResolver.openOutputStream(destination, "wt")
                        ?: error(context.getString(R.string.subtitle_export_failed))
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(subtitles) }
                }
                completed = true
                state.value = state.value!!.copy(busy = false, saved = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = state.value!!.copy(busy = false,
                    message = e.message ?: context.getString(R.string.subtitle_export_failed))
            } finally {
                if (!completed) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, destination) }
                }
            }
        }
    }
}
