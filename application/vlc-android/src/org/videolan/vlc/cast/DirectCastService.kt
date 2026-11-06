/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*
import org.json.JSONObject
import org.videolan.vlc.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.Charset
import java.util.UUID

internal data class DirectCastState(
    val active: Boolean = false, val connected: Boolean = false, val preparing: Boolean = false,
    val message: String = "Choose your TV or Chromecast.", val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val subtitle: Boolean = false,
    val title: String = "", val device: String = "", val mediaUri: String = "",
    val volume: Double = 1.0, val positionUpdatedAt: Long = SystemClock.elapsedRealtime(),
    val error: String? = null
)

/** Owns local file serving while the controller is closed or the screen is off. */
class DirectCastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resources = Any()
    private var destroyed = false
    private var job: Job? = null
    private var directory: File? = null
    private var conversionJob: Job? = null
    private val retiredServers = mutableListOf<CastFileServer>()
    private var server: CastFileServer? = null
    private var connection: CastConnection? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var current = DirectCastState()
    @Volatile private var subtitleUrl: String? = null
    @Volatile private var subtitleText: String? = null
    private var localAddress: InetAddress? = null
    private var mediaUrl = ""
    private var mediaMime = ""
    private var title = ""
    private var device = ""

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Casting", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(): Notification {
        val open = Intent(this, DirectCastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val stop = Intent(this, DirectCastService::class.java).setAction(STOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_vlc)
            .setContentTitle("VLC Plus — $device").setContentText(current.message)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, flags))
            .addAction(0, "Disconnect", PendingIntent.getService(this, 1, stop, flags)).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CONNECT -> {
                if (current.active) return START_NOT_STICKY
                device = intent.getStringExtra("device") ?: "TV"
                title = intent.getStringExtra("title") ?: "Video"
                update(DirectCastState(active = true, preparing = true, message = "Preparing to cast…",
                    position = intent.getLongExtra("position", 0), duration = intent.getLongExtra("duration", 0),
                    title = title, device = device, mediaUri = intent.getStringExtra("uri") ?: ""))
                val n = notification()
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NOTIFICATION, n)
                val uri = intent.getStringExtra("uri")
                val host = intent.getStringExtra("host")
                if (uri == null || host == null || Build.VERSION.SDK_INT < 21) { failure("Open a local video before casting"); return START_NOT_STICKY }
                val port = intent.getIntExtra("port", 8009)
                val convert = intent.getBooleanExtra("convert", false)
                val subtitle = intent.getStringExtra("subtitle")
                job = scope.launch {
                    val ctx = currentCoroutineContext()
                    try {
                        acquireLocks()
                        ctx.ensureActive()
                        val folder = File(cacheDir, "direct-cast-${UUID.randomUUID()}").apply { check(mkdirs()) { "Temporary storage is unavailable" } }
                        directory = folder
                        val original = if (!convert) CastInput.open(this@DirectCastService, Uri.parse(uri)) else null
                        val prepared = if (convert) CastAudioPreparation.prepare(this@DirectCastService, Uri.parse(uri), folder,
                            { ctx.ensureActive() }, { message -> update(current.copy(message = message)) }) else null
                        ctx.ensureActive()
                        val address = InetAddress.getByName(host)
                        val local = DatagramSocket().use { it.connect(address, port); it.localAddress }
                        localAddress = local
                        require(!local.isAnyLocalAddress) { "No network route to this receiver" }
                        // Validate URI/encoding before opening a listening socket.
                        subtitleText = subtitle?.let { readSubtitle(Uri.parse(it)) }
                        ctx.ensureActive()
                        val source = original?.source ?: CastFileServer.FileSource(requireNotNull(prepared).file)
                        val mime = original?.mime ?: requireNotNull(prepared).mime
                        val http = CastFileServer(local, source, mime)
                        val captions = subtitleText?.let { http.setSubtitles(it) }
                        val client = CastConnection(address, port,
                            ready = { loadCurrent() },
                            status = { receiveStatus(it) },
                            failed = { failure(it) },
                            volumeChanged = { update(current.copy(volume = it)) })
                        synchronized(resources) {
                            if (destroyed || !ctx.isActive) { http.close(); client.close(); return@launch }
                            server = http; connection = client
                            mediaUrl = http.mediaUrl(); mediaMime = mime; subtitleUrl = captions
                            update(current.copy(preparing = false, message = "Connecting to $device…", subtitle = captions != null))
                            client.start()
                        }
                    } catch (_: CancellationException) {
                        // Destruction owns state and resource cleanup.
                    } catch (e: Exception) { failure(e.message ?: "Could not prepare casting") }
                }
            }
            STOP -> {
                val client = connection
                scope.launch {
                    runCatching { client?.command("STOP") }
                    update(current.copy(active = false, connected = false, preparing = false, message = "Disconnected"))
                    stopSelf()
                }
            }
            PLAY, PAUSE -> remote { it.command(if (intent.action == PLAY) "PLAY" else "PAUSE") }
            SEEK -> remote { it.command("SEEK", intent.getLongExtra("position", 0).coerceIn(0, current.duration.coerceAtLeast(0)), current.playing) }
            VOLUME -> remote { it.setVolume(intent.getDoubleExtra("volume", current.volume)) }
            CONVERT -> prepareAudioWhileCasting()
            SUBTITLE -> if (current.connected && !current.preparing) scope.launch {
                try {
                    val uri = intent.getStringExtra("subtitle")
                    subtitleText = if (uri == null) null else readSubtitle(Uri.parse(uri))
                    subtitleUrl = subtitleText?.let { server?.setSubtitles(it) }
                    update(current.copy(subtitle = subtitleUrl != null, error = null))
                    loadCurrent()
                } catch (e: Exception) { val message = e.message ?: "Could not load subtitles"; update(current.copy(message = message, error = message)) }
            }
        }
        return START_NOT_STICKY
    }

    private fun remote(block: (CastConnection) -> Unit) {
        scope.launch { try { connection?.let(block) } catch (e: Exception) { failure(e.message ?: "The receiver did not respond") } }
    }

    private fun loadCurrent() {
        connection?.load(mediaUrl, mediaMime, title, current.position, subtitleUrl, !current.connected || current.playing)
    }

    private fun prepareAudioWhileCasting() {
        if (!current.connected || current.preparing || conversionJob?.isActive == true) return
        update(current.copy(preparing = true, message = "Converting audio for TV…", error = null))
        conversionJob = scope.launch {
            val ctx = currentCoroutineContext()
            var candidate: CastFileServer? = null
            try {
                val root = requireNotNull(directory)
                val folder = File(root, "audio-${UUID.randomUUID()}").apply { check(mkdirs()) }
                val prepared = CastAudioPreparation.prepare(this@DirectCastService, Uri.parse(current.mediaUri), folder,
                    { ctx.ensureActive() }, { update(current.copy(message = it)) })
                ctx.ensureActive()
                val http = CastFileServer(requireNotNull(localAddress), prepared.file, prepared.mime)
                candidate = http
                val captions = subtitleText?.let { http.setSubtitles(it) }
                synchronized(resources) {
                    if (destroyed || !ctx.isActive) return@launch
                    server?.let { retiredServers.add(it) }; server = http; candidate = null
                    mediaUrl = http.mediaUrl(); mediaMime = prepared.mime; subtitleUrl = captions
                    val now = SystemClock.elapsedRealtime()
                    val position = current.position + if (current.playing) now - current.positionUpdatedAt else 0
                    update(current.copy(preparing = false, message = "Switching to compatible audio…", position = position, positionUpdatedAt = now))
                    loadCurrent()
                }
            } catch (_: CancellationException) { }
            catch (e: Exception) {
                val message = e.message ?: "Audio conversion failed"
                update(current.copy(preparing = false, message = message, error = message))
            } finally { candidate?.close() }
        }
    }

    private fun receiveStatus(data: JSONObject) {
        val playing = data.optString("playerState")
        val idle = data.optString("idleReason")
        if (idle == "ERROR") { failure("The receiver cannot play this file. Tap Convert to prepare compatible audio, or use VLC casting."); return }
        if (playing == "IDLE") {
            update(current.copy(active = false, connected = false, preparing = false,
                message = if (idle == "FINISHED") "Playback finished" else "Playback stopped on $device"))
            stopSelf(); return
        }
        val duration = data.optJSONObject("media")?.optDouble("duration", 0.0)?.let { (it * 1000).toLong() } ?: current.duration
        if (data.optJSONObject("media")?.optString("contentId") == mediaUrl) synchronized(resources) {
            retiredServers.forEach { it.close() }; retiredServers.clear()
        }
        update(current.copy(connected = data.has("mediaSessionId"), playing = playing == "PLAYING",
            position = (data.optDouble("currentTime", current.position / 1000.0) * 1000).toLong(),
            positionUpdatedAt = SystemClock.elapsedRealtime(),
            duration = if (duration > 0) duration else current.duration,
            message = if (current.preparing) current.message else when (playing) { "PLAYING" -> "Playing on $device"; "PAUSED" -> "Paused on $device"; "BUFFERING" -> "Buffering on $device…"; else -> current.message }))
    }

    private fun readSubtitle(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 5 * 1024 * 1024) { "Subtitle file is too large" }; output.write(buffer, 0, n) }
            output.toByteArray()
        } ?: error("The subtitle file cannot be opened")
        val charset = if (bytes.size >= 2 && ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) || (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))) Charset.forName("UTF-16") else Charsets.UTF_8
        return CastSubtitles.toWebVtt(String(bytes, charset))
    }

    private fun acquireLocks() {
        synchronized(resources) {
            if (destroyed) return
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VLCPlus:cast").apply { acquire(6 * 60 * 60 * 1000L) }
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VLCPlus:cast").apply { acquire() }
        }
    }

    @Synchronized private fun update(next: DirectCastState) {
        if (destroyed) return
        current = next; state.postValue(next)
        if (next.active) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification())
    }

    private fun failure(message: String) {
        Log.w("VLCPlus/Cast", message)
        update(current.copy(active = false, connected = false, preparing = false, message = message, error = message))
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(resources) {
            destroyed = true
            connection?.close(); server?.close()
            retiredServers.forEach { it.close() }; retiredServers.clear()
            if (wake?.isHeld == true) wake?.release()
            if (wifi?.isHeld == true) wifi?.release()
        }
        scope.coroutineContext[Job]?.invokeOnCompletion { directory?.deleteRecursively() }
        scope.cancel()
        if (current.active) state.postValue(current.copy(active = false, connected = false, preparing = false, message = "Disconnected"))
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    companion object {
        internal val state = MutableLiveData(DirectCastState())
        const val CONNECT = "vlcplus.cast.CONNECT"
        const val STOP = "vlcplus.cast.STOP"
        const val PLAY = "vlcplus.cast.PLAY"
        const val PAUSE = "vlcplus.cast.PAUSE"
        const val SEEK = "vlcplus.cast.SEEK"
        const val SUBTITLE = "vlcplus.cast.SUBTITLE"
        const val CONVERT = "vlcplus.cast.CONVERT"
        const val VOLUME = "vlcplus.cast.VOLUME"
        private const val CHANNEL = "vlcplus_direct_cast"
        private const val NOTIFICATION = 7319
    }
}
