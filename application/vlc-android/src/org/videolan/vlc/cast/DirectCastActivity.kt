/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.gui.browser.EXTRA_MRL
import org.videolan.vlc.gui.browser.FilePickerActivity
import org.videolan.vlc.gui.browser.KEY_MEDIA
import org.videolan.vlc.util.FileUtils
import java.util.ArrayDeque

/** Binds the compact controller to receiver discovery, the file browser and the service. */
class DirectCastActivity : AppCompatActivity(), CastControlsView.Actions {
    private lateinit var controls: CastControlsView
    private lateinit var nsd: NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var pendingSubtitle: String? = null
    private var convertBeforeConnect = false
    private var started = false
    private var resolving = false
    private var discoveryGeneration = 0
    private var deviceDialog: AlertDialog? = null
    private var deviceAdapter: ArrayAdapter<String>? = null
    private var lastError: String? = null
    private var previousVolume = 1.0
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val receivers = linkedMapOf<String, NsdServiceInfo>()
    private var current = DirectCastState()
    private val clock = object : Runnable {
        override fun run() { render(); if (started) controls.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CastControlsView.prepareWindow(window)
        pendingSubtitle = savedInstanceState?.getString("subtitle")
        convertBeforeConnect = savedInstanceState?.getBoolean("convert") ?: false
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        controls = CastControlsView(this, this)
        setContentView(controls)
        controls.requestApplyInsets()
        DirectCastService.state.observe(this) {
            current = it; render()
            if (it.error != null && it.error != lastError) {
                lastError = it.error
                AlertDialog.Builder(this).setTitle("Casting").setMessage(it.error).setPositiveButton("OK", null).show()
            } else if (it.error == null) lastError = null
        }
    }

    private fun position(): Long = (current.position + if (current.playing) SystemClock.elapsedRealtime() - current.positionUpdatedAt else 0)
        .coerceIn(0, current.duration.coerceAtLeast(0))

    private fun render() {
        controls.render(current.title.ifBlank { intent.getStringExtra("title") ?: "Cast to TV" }, current.device,
            current.message, current.active, current.connected, current.preparing, current.playing,
            position(), current.duration, current.volume, if (current.connected) current.subtitle else pendingSubtitle != null)
    }

    override fun back() = finish()
    override fun stop() = command(DirectCastService.STOP)
    override fun playPause() = command(if (current.playing) DirectCastService.PAUSE else DirectCastService.PLAY)
    override fun jump(delta: Long) = seek(position() + delta)
    override fun seek(position: Long) = command(DirectCastService.SEEK) { putExtra("position", position.coerceIn(0, current.duration.coerceAtLeast(0))) }
    override fun volume(delta: Double) { if (current.connected) command(DirectCastService.VOLUME) { putExtra("volume", (current.volume + delta).coerceIn(0.0, 1.0)) } }
    override fun mute() {
        if (!current.connected) return
        if (current.volume > 0) { previousVolume = current.volume; command(DirectCastService.VOLUME) { putExtra("volume", 0.0) } }
        else command(DirectCastService.VOLUME) { putExtra("volume", previousVolume) }
    }

    override fun devices() {
        if (current.active) {
            AlertDialog.Builder(this).setTitle(current.device.ifBlank { "Casting" })
                .setMessage("Stop the current cast to choose another device.")
                .setPositiveButton("Stop casting") { _, _ -> stop() }.setNegativeButton("Close", null).show()
            return
        }
        if (deviceDialog?.isShowing == true) return
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        refreshDeviceList()
        deviceDialog = AlertDialog.Builder(this).setTitle("Cast to")
            .setAdapter(deviceAdapter) { _, position -> receivers.values.toList().getOrNull(position)?.let { connect(it) } }
            .setNegativeButton("Cancel", null).create().apply { show() }
        if (receivers.isEmpty()) Toast.makeText(this, "Searching for your TV and Chromecast…", Toast.LENGTH_SHORT).show()
    }

    override fun subtitles() {
        if (current.preparing) return
        AlertDialog.Builder(this).setTitle("Subtitles")
            .setItems(arrayOf("Choose from movie folder", "Choose using Files", "Off")) { _, index ->
                when (index) {
                    0 -> openMovieFolder()
                    1 -> openSystemPicker()
                    else -> { pendingSubtitle = null; if (current.connected) command(DirectCastService.SUBTITLE); render() }
                }
            }.show()
    }

    private fun movieUri(): Uri? = (intent.getStringExtra("uri") ?: current.mediaUri.takeIf { it.isNotBlank() })?.let { Uri.parse(it) }

    private fun openMovieFolder() {
        lifecycleScope.launch {
            val uri = movieUri()
            val resolved = withContext(Dispatchers.IO) {
                if (uri?.scheme == "content") runCatching { FileUtils.getPathFromURI(uri) }.getOrNull()?.takeIf { it.startsWith("file:") }?.let(Uri::parse) else uri
            }
            val parent = resolved?.takeIf { it.scheme == "file" || it.scheme == null }?.let { FileUtils.getParent(it.toString()) }
            if (parent == null) { openSystemPicker(); return@launch }
            val directory = MLServiceLocator.getAbstractMediaWrapper(Uri.parse(parent))
            startActivityForResult(Intent(this@DirectCastActivity, FilePickerActivity::class.java).putExtra(KEY_MEDIA, directory), PICK_LOCAL_SUBTITLE)
        }
    }

    private fun openSystemPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        val path = movieUri()?.path
        if (Build.VERSION.SDK_INT >= 26 && path != null && path.startsWith("/storage/")) {
            val rest = if (path.startsWith("/storage/emulated/0/")) "primary:" + path.removePrefix("/storage/emulated/0/").substringBeforeLast('/', "")
                else path.removePrefix("/storage/").substringBefore('/') + ":" + path.removePrefix("/storage/").substringAfter('/', "").substringBeforeLast('/', "")
            picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", rest))
        }
        startActivityForResult(picker, PICK_SYSTEM_SUBTITLE)
    }

    override fun convert() {
        if (current.preparing) return
        AlertDialog.Builder(this).setTitle("Convert audio for TV")
            .setMessage(if (current.connected) "Prepare compatible stereo audio while the current picture keeps playing. When ready, playback switches at your current position. This needs time and temporary storage."
                else "Prepare compatible audio and a seekable video file before playback. Use this if the receiver cannot play the original or has no sound. This can take several minutes.")
            .setPositiveButton("Convert") { _, _ ->
                if (current.connected) command(DirectCastService.CONVERT)
                else { convertBeforeConnect = true; devices() }
            }.setNegativeButton("Cancel", null).show()
    }

    override fun help() {
        AlertDialog.Builder(this).setTitle("Casting help")
            .setMessage("Fast cast sends the original file first.\n\nNo sound? Tap Convert to prepare stereo audio.\n\nSubtitle files: SRT or WebVTT. Choose from movie folder opens beside your video.\n\nStop cancels casting and preparation. Use VLC casting is the original fallback.")
            .setPositiveButton("OK", null).show()
    }

    override fun legacy() {
        if (current.active) { Toast.makeText(this, "Stop the current cast first.", Toast.LENGTH_SHORT).show(); return }
        setResult(RESULT_FIRST_USER); finish()
    }

    private fun command(action: String, extras: Intent.() -> Unit = {}) {
        if (!current.active) return
        startService(Intent(this, DirectCastService::class.java).setAction(action).apply(extras))
    }

    private fun connect(info: NsdServiceInfo) {
        if (current.active) return
        val uri = movieUri()?.toString()
        if (uri == null) { Toast.makeText(this, "Open a local video, then tap Cast.", Toast.LENGTH_LONG).show(); return }
        val host = info.host?.hostAddress ?: return
        PlaybackService.serviceFlow.value?.pause(); PlaybackService.renderer.value = null
        val service = Intent(this, DirectCastService::class.java).setAction(DirectCastService.CONNECT)
            .putExtra("uri", uri).putExtra("host", host).putExtra("port", info.port)
            .putExtra("device", name(info)).putExtra("title", intent.getStringExtra("title") ?: current.title)
            .putExtra("position", if (current.duration > 0) position() else intent.getLongExtra("position", 0))
            .putExtra("duration", current.duration.takeIf { it > 0 } ?: intent.getLongExtra("duration", 0))
            .putExtra("convert", convertBeforeConnect).putExtra("subtitle", pendingSubtitle)
        convertBeforeConnect = false
        ContextCompat.startForegroundService(this, service)
    }

    private fun name(info: NsdServiceInfo): String = info.attributes["fn"]?.let { String(it, Charsets.UTF_8) } ?: info.serviceName
    private fun refreshDeviceList() { deviceAdapter?.apply { clear(); addAll(receivers.values.map { name(it) }); notifyDataSetChanged() } }

    override fun onStart() {
        super.onStart(); started = true; discoveryGeneration++
        controls.post(clock)
        val generation = discoveryGeneration
        multicast = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("VLCPlus:discovery").apply { setReferenceCounted(false); acquire() }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { runOnUiThread { if (!current.active) Toast.makeText(this@DirectCastActivity, "Discovery failed. Reopen Cast to retry.", Toast.LENGTH_LONG).show() } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { runOnUiThread { if (started && generation == discoveryGeneration) { pending.add(info); resolveNext(generation) } } }
            override fun onServiceLost(info: NsdServiceInfo) { runOnUiThread { if (generation == discoveryGeneration) { receivers.remove(info.serviceName); refreshDeviceList() } } }
        }
        listener = discovery
        try { nsd.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (_: Exception) { Toast.makeText(this, "Could not discover receivers. Try VLC casting.", Toast.LENGTH_LONG).show() }
    }

    private fun resolveNext(generation: Int) {
        if (resolving || !started || generation != discoveryGeneration || pending.isEmpty()) return
        val info = pending.removeFirst(); resolving = true
        try { nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { runOnUiThread { if (generation == discoveryGeneration) { resolving = false; resolveNext(generation) } } }
            override fun onServiceResolved(service: NsdServiceInfo) { runOnUiThread { if (started && generation == discoveryGeneration) { resolving = false; receivers[service.serviceName] = service; refreshDeviceList(); resolveNext(generation) } } }
        }) } catch (_: Exception) { resolving = false; resolveNext(generation) }
    }

    override fun onStop() {
        started = false; discoveryGeneration++; pending.clear(); resolving = false
        controls.removeCallbacks(clock)
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        deviceDialog?.dismiss(); deviceDialog = null
        super.onStop()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val selected = when (requestCode) {
            PICK_LOCAL_SUBTITLE -> data.getStringExtra(EXTRA_MRL)?.let(Uri::parse)
            PICK_SYSTEM_SUBTITLE -> data.data
            else -> null
        } ?: return
        pendingSubtitle = selected.toString()
        if (selected.scheme == "content") runCatching { contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (current.connected) command(DirectCastService.SUBTITLE) { putExtra("subtitle", pendingSubtitle) }
        else Toast.makeText(this, "Subtitles selected", Toast.LENGTH_SHORT).show()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("subtitle", pendingSubtitle); outState.putBoolean("convert", convertBeforeConnect)
        super.onSaveInstanceState(outState)
    }
    companion object { const val REQUEST = 7391; private const val PICK_SYSTEM_SUBTITLE = 7392; private const val PICK_LOCAL_SUBTITLE = 7393 }
}
