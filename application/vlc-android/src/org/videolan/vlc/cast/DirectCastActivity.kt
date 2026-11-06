/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.videolan.vlc.PlaybackService
import java.util.ArrayDeque
import java.util.Locale

/** Device chooser and remote controls. The service, not this screen, owns streaming. */
class DirectCastActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var devices: LinearLayout
    private lateinit var pause: Button
    private lateinit var back: Button
    private lateinit var forward: Button
    private lateinit var disconnect: Button
    private lateinit var subtitles: Button
    private lateinit var clearSubtitles: Button
    private lateinit var prepare: CheckBox
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var nsd: NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var pendingSubtitle: String? = null
    private var dragging = false
    private var started = false
    private var resolving = false
    private var discoveryGeneration = 0
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val receivers = linkedMapOf<String, NsdServiceInfo>()
    private var current = DirectCastState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSubtitle = savedInstanceState?.getString("subtitle")
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; root.addView(this) }
        fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; setOnClickListener { action() }; root.addView(this) }
        text("Cast to TV", 25f)
        status = text("Looking for your TV and Chromecast…")
        prepare = CheckBox(this).apply { text = "Prepare audio for TV"; root.addView(this) }
        text("Use this if the video has no sound. Preparation keeps the video quality and supports seeking, but needs time and temporary storage.", 13f)
        devices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(devices) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        time = text("0:00 / 0:00")
        seek = SeekBar(this).apply { max = 1000; root.addView(this) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                command(DirectCastService.SEEK) { putExtra("position", current.duration * bar.progress / 1000) }
            }
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun control(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }; row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        back = control("−15 s") { jump(-15000) }
        pause = control("Pause") { command(if (current.playing) DirectCastService.PAUSE else DirectCastService.PLAY) }
        forward = control("+15 s") { jump(15000) }
        root.addView(row)
        subtitles = button("Choose subtitles (.srt / .vtt)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK_SUBTITLE)
        }
        clearSubtitles = button("Turn subtitles off") {
            pendingSubtitle = null
            if (current.connected) command(DirectCastService.SUBTITLE)
            subtitles.text = "Choose subtitles (.srt / .vtt)"
        }
        disconnect = button("Disconnect") { command(DirectCastService.STOP) }
        button("Use VLC casting") {
            if (current.active) {
                Toast.makeText(this, "Disconnect the current cast first.", Toast.LENGTH_SHORT).show()
                return@button
            }
            setResult(RESULT_FIRST_USER); finish()
        }
        setContentView(root)
        DirectCastService.state.observe(this) { state ->
            current = state
            status.text = state.message
            pause.text = if (state.playing) "Pause" else "Play"
            pause.isEnabled = state.connected
            back.isEnabled = state.connected && state.duration > 0
            forward.isEnabled = back.isEnabled
            seek.isEnabled = back.isEnabled
            prepare.isEnabled = !state.active
            disconnect.isEnabled = state.active
            subtitles.isEnabled = !state.preparing
            clearSubtitles.isEnabled = !state.preparing && (state.subtitle || pendingSubtitle != null)
            if (!dragging && state.duration > 0) seek.progress = (state.position * 1000 / state.duration).toInt().coerceIn(0, 1000)
            time.text = "${format(state.position)} / ${format(state.duration)}"
            renderReceivers()
        }
    }

    private fun jump(offset: Long) = command(DirectCastService.SEEK) {
        putExtra("position", (current.position + offset).coerceIn(0, current.duration.coerceAtLeast(0)))
    }

    private fun command(action: String, extras: Intent.() -> Unit = {}) {
        if (!current.active) return
        startService(Intent(this, DirectCastService::class.java).setAction(action).apply(extras))
    }

    private fun connect(info: NsdServiceInfo) {
        if (current.active) return
        val uri = intent.getStringExtra("uri")
        if (uri == null) { status.text = "Open a local video in VLC Plus, then tap its Cast button."; return }
        val host = info.host?.hostAddress ?: return
        PlaybackService.serviceFlow.value?.pause()
        PlaybackService.renderer.value = null
        val service = Intent(this, DirectCastService::class.java).setAction(DirectCastService.CONNECT)
            .putExtra("uri", uri).putExtra("host", host).putExtra("port", info.port)
            .putExtra("device", name(info)).putExtra("title", intent.getStringExtra("title"))
            .putExtra("position", intent.getLongExtra("position", 0)).putExtra("duration", intent.getLongExtra("duration", 0))
            .putExtra("convert", prepare.isChecked).putExtra("subtitle", pendingSubtitle)
        ContextCompat.startForegroundService(this, service)
    }

    private fun name(info: NsdServiceInfo): String =
        info.attributes["fn"]?.let { String(it, Charsets.UTF_8) } ?: info.serviceName

    private fun renderReceivers() {
        if (!::devices.isInitialized) return
        devices.removeAllViews()
        receivers.values.forEach { info ->
            devices.addView(Button(this).apply { text = name(info); isEnabled = !current.active; setOnClickListener { connect(info) } })
        }
    }

    override fun onStart() {
        super.onStart(); started = true; discoveryGeneration++
        val generation = discoveryGeneration
        multicast = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("VLCPlus:discovery").apply { setReferenceCounted(false); acquire() }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { runOnUiThread { if (!current.active) status.text = "Discovery failed. Reopen this screen to retry." } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { runOnUiThread {
                if (started && generation == discoveryGeneration) { pending.add(info); resolveNext(generation) }
            } }
            override fun onServiceLost(info: NsdServiceInfo) { runOnUiThread {
                if (generation == discoveryGeneration) { receivers.remove(info.serviceName); renderReceivers() }
            } }
        }
        listener = discovery
        try { nsd.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, discovery) }
        catch (_: Exception) { status.text = "Could not discover receivers. Use VLC casting or reopen this screen." }
    }

    private fun resolveNext(generation: Int) {
        if (resolving || !started || generation != discoveryGeneration || pending.isEmpty()) return
        val info = pending.removeFirst(); resolving = true
        try { nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { runOnUiThread {
                if (generation == discoveryGeneration) { resolving = false; resolveNext(generation) }
            } }
            override fun onServiceResolved(service: NsdServiceInfo) { runOnUiThread {
                if (started && generation == discoveryGeneration) {
                    resolving = false; receivers[service.serviceName] = service; renderReceivers(); resolveNext(generation)
                }
            } }
        }) } catch (_: Exception) { resolving = false; resolveNext(generation) }
    }

    override fun onStop() {
        started = false; discoveryGeneration++; pending.clear(); resolving = false
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
        multicast?.let { if (it.isHeld) it.release() }; multicast = null
        super.onStop()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_SUBTITLE && resultCode == RESULT_OK && data?.data != null) {
            pendingSubtitle = data.data.toString()
            runCatching { contentResolver.takePersistableUriPermission(data.data!!, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            subtitles.text = "Subtitles selected — choose another"
            clearSubtitles.isEnabled = true
            if (current.connected) command(DirectCastService.SUBTITLE) { putExtra("subtitle", pendingSubtitle) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("subtitle", pendingSubtitle); super.onSaveInstanceState(outState) }

    private fun format(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms.coerceAtLeast(0) / 60000, (ms.coerceAtLeast(0) / 1000) % 60)

    companion object { const val REQUEST = 7391; private const val PICK_SUBTITLE = 7392 }
}
