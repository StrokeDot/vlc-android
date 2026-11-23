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
import org.videolan.vlc.R
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
    private var lastErrorId = 0
    private var previousVolume = 1.0
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val receivers get() = knownReceivers
    private var current = DirectCastState()
    private val clock = object : Runnable {
        // Only the elapsed time moves between status updates, and only while playing.
        override fun run() { if (current.playing || legacyActive()) render(); if (started) controls.postDelayed(this, 1000) }
    }

    /** Name of the receiver used by the original LibVLC casting, if that path is active. */
    private fun legacyDevice(): String? = PlaybackService.renderer.value?.displayName?.ifBlank { "TV" }
    private fun legacyActive() = !current.active && PlaybackService.hasRenderer()
    private val playback get() = PlaybackService.serviceFlow.value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CastControlsView.prepareWindow(window)
        pendingSubtitle = savedInstanceState?.getString("subtitle")
        convertBeforeConnect = savedInstanceState?.getBoolean("convert") ?: false
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        controls = CastControlsView(this, this)
        setContentView(controls)
        controls.requestApplyInsets()
        // Errors raised before this screen opened belong to an earlier session; don't replay them.
        lastErrorId = DirectCastService.state.value?.errorId ?: 0
        DirectCastService.state.observe(this) {
            current = it; render()
            if (it.error != null && it.errorId != lastErrorId) {
                lastErrorId = it.errorId
                AlertDialog.Builder(this).setTitle("Casting").setMessage(it.error).setPositiveButton("OK", null).show()
            }
        }
        // The original VLC casting can be active (e.g. started from the library); reflect it instead of
        // showing a dead, grayed-out controller.
        PlaybackService.renderer.observe(this) { render() }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)) autoConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); render()
        if (intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)) autoConnect()
    }

    private fun autoConnect() {
        val host = intent.getStringExtra("target_host") ?: return
        intent.removeExtra(EXTRA_AUTO_CONNECT)
        if (!current.active && movieUri() != null)
            connect(host, intent.getIntExtra("target_port", 8009), intent.getStringExtra("target_name") ?: "TV")
    }

    private fun position(): Long = if (legacyActive()) playback?.getTime()?.coerceAtLeast(0) ?: 0 else (current.position + if (current.playing) SystemClock.elapsedRealtime() - current.positionUpdatedAt else 0)
        .coerceIn(0, current.duration.coerceAtLeast(0))

    private fun render() {
        val legacy = legacyActive()
        val pick = !current.active && !legacy && movieUri() == null
        // While casting, the screen always describes what is actually on the TV.
        val title = (if (current.active || sameMovie()) current.title else "").ifBlank { intent.getStringExtra("title") ?: "Cast to TV" }
        val message = when {
            legacy -> "VLC casting is active on ${legacyDevice()}. Tap Stop to end it, or choose a device to switch."
            pick -> DirectCastService.pendingTarget?.let { "${it.name} selected. Open a video to start casting." }
                ?: "Choose your TV now; the next video you open will cast to it."
            else -> current.message
        }
        val service = playback
        if (legacy && service != null) {
            // Drive the original VLC cast through PlaybackService so its controls work here too.
            controls.render(service.title ?: title, legacyDevice() ?: "", message, true, true, false, service.isPlaying,
                position(), service.length, service.volume.coerceIn(0, 100) / 100.0, false)
            return
        }
        controls.render(title, current.device, message, current.active || legacy, current.connected, current.preparing, current.playing,
            position(), current.duration, current.volume, if (current.connected) current.subtitle else pendingSubtitle != null)
    }

    override fun back() = finish()
    override fun stop() {
        if (legacyActive()) { playback?.pause(); PlaybackService.renderer.value = null; render(); return }
        DirectCastService.pendingTarget = null
        command(DirectCastService.STOP)
    }
    override fun playPause() {
        if (legacyActive()) { playback?.let { if (it.isPlaying) it.pause() else it.play() }; controls.postDelayed({ render() }, 300); return }
        command(if (current.playing) DirectCastService.PAUSE else DirectCastService.PLAY)
    }
    override fun jump(delta: Long) = seek(position() + delta)
    override fun seek(position: Long) {
        if (legacyActive()) { playback?.let { it.seek(position.coerceIn(0, it.length.coerceAtLeast(0)), fromUser = true) }; render(); return }
        command(DirectCastService.SEEK) { putExtra("position", position.coerceIn(0, current.duration.coerceAtLeast(0))) }
    }
    override fun volume(delta: Double) {
        if (legacyActive()) { playback?.let { it.setVolume((it.volume + (delta * 100).toInt()).coerceIn(0, 100)) }; render(); return }
        if (current.connected) command(DirectCastService.VOLUME) { putExtra("volume", (current.volume + delta).coerceIn(0.0, 1.0)) }
    }
    override fun mute() {
        if (legacyActive()) { playback?.let { if (it.volume > 0) { previousVolume = it.volume / 100.0; it.setVolume(0) } else it.setVolume((previousVolume * 100).toInt()) }; render(); return }
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
            .setItems(arrayOf(getString(R.string.use_embedded_subtitles), getString(R.string.extract_subtitles),
                "Choose from movie folder", "Choose using Files", "Appearance & preview", "Off")) { _, index ->
                when (index) {
                    0 -> openEmbeddedSubtitles()
                    1 -> movieUri()?.let { EmbeddedSubtitleExportDialog.show(this, it, intent.getStringExtra("title") ?: current.title) }
                    2 -> openMovieFolder()
                    3 -> openSystemPicker()
                    4 -> CastSubtitleAppearance.show(this) { if (current.connected) command(DirectCastService.STYLE) }
                    else -> { pendingSubtitle = null; if (current.connected) command(DirectCastService.SUBTITLE); render() }
                }
            }.show()
    }

    private fun openEmbeddedSubtitles() {
        val movie = movieUri() ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CastEmbeddedSubtitles.tracks(this@DirectCastActivity, movie) }
            }
            if (isFinishing || isDestroyed) return@launch
            val tracks = result.getOrElse {
                AlertDialog.Builder(this@DirectCastActivity).setTitle("Embedded subtitles")
                    .setMessage(it.message ?: "Could not inspect this movie's subtitle tracks.")
                    .setPositiveButton("OK", null).show()
                return@launch
            }
            if (tracks.isEmpty()) {
                AlertDialog.Builder(this@DirectCastActivity).setTitle("Embedded subtitles")
                    .setMessage("No compatible embedded text subtitles were detected. Bitmap subtitles (PGS/VobSub) are not supported. You can still choose an external SRT or WebVTT file.")
                    .setPositiveButton("OK", null).show()
                return@launch
            }
            AlertDialog.Builder(this@DirectCastActivity).setTitle("Choose embedded subtitles")
                .setItems(tracks.map { it.label }.toTypedArray()) { _, selection ->
                    CastSubtitleAppearance.show(this@DirectCastActivity) {
                        pendingSubtitle = "embedded:" + tracks[selection].index
                        if (current.connected) command(DirectCastService.SUBTITLE) { putExtra("subtitle", pendingSubtitle) }
                        else Toast.makeText(this@DirectCastActivity, "Embedded subtitles ready for casting", Toast.LENGTH_SHORT).show()
                        render()
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun sameMovie() = movieUri()?.toString() == current.mediaUri

    // A finished session's media must not be recast by the library button, so only fall back to it while active.
    private fun movieUri(): Uri? = (intent.getStringExtra("uri") ?: current.mediaUri.takeIf { current.active && it.isNotBlank() }
        ?: playback?.currentMediaLocation?.takeIf { PlaybackService.hasRenderer() })?.let { Uri.parse(it) }

    private fun openMovieFolder() {
        lifecycleScope.launch {
            val uri = movieUri()
            val resolved = withContext(Dispatchers.IO) {
                uri?.let { CastMovieLocation.localFile(this@DirectCastActivity, it) }
            }
            val parent = resolved?.takeIf { it.scheme == "file" || it.scheme == null }?.let { FileUtils.getParent(it.toString()) }
            if (parent == null) { openSystemPicker(); return@launch }
            val directory = MLServiceLocator.getAbstractMediaWrapper(Uri.parse(parent))
            startActivityForResult(Intent(this@DirectCastActivity, FilePickerActivity::class.java).putExtra(KEY_MEDIA, directory), PICK_LOCAL_SUBTITLE)
        }
    }

    private fun openSystemPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        if (Build.VERSION.SDK_INT >= 26) movieUri()?.let { CastMovieLocation.initialFolder(it) }?.let {
            picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it)
        }
        startActivityForResult(picker, PICK_SYSTEM_SUBTITLE)
    }

    override fun convert() {
        if (current.preparing) return
        AlertDialog.Builder(this).setTitle("Convert audio for TV")
            .setMessage(if (current.connected) "Streams a TV-compatible version within seconds: the picture is copied and only the audio is converted, continuing at your current position. MKV movies stream right away; other files, or a TV that rejects the stream, use the full conversion, which takes longer."
                else "Starts with a TV-compatible stream within seconds (picture copied, audio converted). Use this if the receiver cannot play the original or has no sound. Files that cannot be streamed use the full conversion, which can take several minutes.")
            .setPositiveButton("Convert") { _, _ ->
                if (current.connected) command(DirectCastService.CONVERT)
                else { convertBeforeConnect = true; devices() }
            }.setNegativeButton("Cancel", null).show()
    }

    override fun help() {
        AlertDialog.Builder(this).setTitle("Casting help")
            .setMessage("Fast cast sends the original file first.\n\nNo sound? Tap Convert: MKV movies switch to a TV-compatible stream in seconds. Tap Convert again for the full conversion.\n\nSubtitles: Extract from movie reads compatible embedded text tracks. External SRT/WebVTT files can still be chosen from the movie folder. Bitmap subtitle formats are not yet supported.\n\nStop cancels casting and preparation. Use VLC casting is the original fallback.")
            .setPositiveButton("OK", null).show()
    }

    override fun legacy() {
        if (current.active) { Toast.makeText(this, "Stop the current cast first.", Toast.LENGTH_SHORT).show(); return }
        DirectCastService.pendingTarget = null
        setResult(RESULT_FIRST_USER); finish()
    }

    private fun command(action: String, extras: Intent.() -> Unit = {}) {
        if (!current.active) return
        startService(Intent(this, DirectCastService::class.java).setAction(action).apply(extras))
    }

    private fun connect(info: NsdServiceInfo) {
        val host = info.host?.hostAddress ?: return
        connect(host, info.port, name(info))
    }

    private fun connect(host: String, port: Int, device: String) {
        if (current.active) return
        val uri = movieUri()?.toString()
        if (uri == null) {
            // Library Cast button: remember the receiver; the player casts the next video to it.
            if (PlaybackService.hasRenderer()) PlaybackService.renderer.value = null
            DirectCastService.pendingTarget = CastTarget(host, port, device)
            Toast.makeText(this, "Open a video to cast it to $device.", Toast.LENGTH_LONG).show()
            render(); return
        }
        // Switching from the original VLC cast continues from where it was.
        val fromLegacy = legacyActive()
        val start = if (fromLegacy || (sameMovie() && current.duration > 0)) position() else intent.getLongExtra("position", 0)
        val movieTitle = intent.getStringExtra("title") ?: (if (fromLegacy) playback?.title else null) ?: current.title
        val movieLength = if (fromLegacy) playback?.length ?: 0 else current.duration.takeIf { sameMovie() && it > 0 } ?: intent.getLongExtra("duration", 0)
        PlaybackService.serviceFlow.value?.pause(); PlaybackService.renderer.value = null
        val service = Intent(this, DirectCastService::class.java).setAction(DirectCastService.CONNECT)
            .putExtra("uri", uri).putExtra("host", host).putExtra("port", port)
            .putExtra("device", device).putExtra("title", movieTitle)
            .putExtra("position", start)
            .putExtra("duration", movieLength)
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
        if (selected.scheme == "content") runCatching { contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        CastSubtitleAppearance.show(this) {
            pendingSubtitle = selected.toString()
            if (current.connected) command(DirectCastService.SUBTITLE) { putExtra("subtitle", pendingSubtitle) }
            else Toast.makeText(this, "Subtitles ready", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("subtitle", pendingSubtitle); outState.putBoolean("convert", convertBeforeConnect)
        super.onSaveInstanceState(outState)
    }
    companion object {
        const val REQUEST = 7391; private const val PICK_SYSTEM_SUBTITLE = 7392; private const val PICK_LOCAL_SUBTITLE = 7393
        const val EXTRA_AUTO_CONNECT = "auto_connect"
        /** Resolved receivers survive between screens so the device list appears instantly. */
        private val knownReceivers = linkedMapOf<String, NsdServiceInfo>()

        /** Single entry point for every Cast button, so none of them can open the old dialog by mistake. */
        fun intent(context: Context, uri: String?, title: String?, position: Long, duration: Long, autoConnect: Boolean = false): Intent {
            val intent = Intent(context, DirectCastActivity::class.java)
                .putExtra("uri", uri).putExtra("title", title).putExtra("position", position).putExtra("duration", duration)
            if (autoConnect) DirectCastService.pendingTarget?.let { target ->
                DirectCastService.pendingTarget = null
                intent.putExtra(EXTRA_AUTO_CONNECT, true).putExtra("target_host", target.host)
                    .putExtra("target_port", target.port).putExtra("target_name", target.name)
            }
            return intent
        }
    }
}
