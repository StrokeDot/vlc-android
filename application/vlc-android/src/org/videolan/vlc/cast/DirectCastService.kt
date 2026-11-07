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
    @Volatile private var destroyed = false
    private var job: Job? = null
    private var directory: File? = null
    private var conversionJob: Job? = null
    private val retiredServers = mutableListOf<Pair<CastFileServer, File?>>()
    private var preparedFolder: File? = null
    private var preparedOutput: CastPreparation.Prepared? = null
    private var server: CastFileServer? = null
    private var connection: CastConnection? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val states = CastStateStore(DirectCastState()) { it.active }
    private val current get() = states.value
    @Volatile private var subtitleUrl: String? = null
    @Volatile private var subtitleText: String? = null
    private var localAddress: InetAddress? = null
    private var mediaUrl = ""
    private var mediaMime = ""
    private var title = ""
    private var device = ""
    @Volatile private var convertedUrlAwaitingPlayback: String? = null
    @Volatile private var conversionHandoffAtMs = 0L
    @Volatile private var playbackRequested = true
    private var bufferingSinceMs = 0L
    private var bufferingAtPositionMs = -1L
    private var bufferingWarningIssued = false

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
                playbackRequested = true
                convertedUrlAwaitingPlayback = null
                conversionHandoffAtMs = 0L
                bufferingSinceMs = 0L
                bufferingAtPositionMs = -1L
                bufferingWarningIssued = false
                update(true) { DirectCastState(active = true, preparing = true, message = "Preparing to cast…",
                    position = intent.getLongExtra("position", 0), duration = intent.getLongExtra("duration", 0),
                    title = title, device = device, mediaUri = intent.getStringExtra("uri") ?: "") }
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
                        val prepared = if (convert) CastPreparedCache.prepare(this@DirectCastService, Uri.parse(uri), folder,
                            { ctx.ensureActive() }, { message -> update { if (current.active) current.copy(message = message) else current } }) else null
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
                            volumeChanged = { update { current.copy(volume = it) } },
                            styleFailed = { message -> update { current.copy(error = message) } })
                        synchronized(resources) {
                            if (destroyed || !ctx.isActive) { http.close(); client.close(); return@launch }
                            server = http; connection = client
                            preparedOutput = prepared
                            mediaUrl = http.mediaUrl(); mediaMime = mime; subtitleUrl = captions
                            update { current.copy(preparing = false, message = "Connecting to $device…", subtitle = captions != null) }
                            client.start()
                        }
                    } catch (_: CancellationException) {
                        // Destruction owns state and resource cleanup.
                    } catch (e: Exception) { failure(e.message ?: "Could not prepare casting") }
                }
            }
            STOP -> {
                convertedUrlAwaitingPlayback = null
                job?.cancel(); conversionJob?.cancel()
                playbackRequested = false
                update { current.copy(active = false, connected = false, preparing = false, message = "Disconnected") }
                val client = connection
                scope.launch {
                    runCatching { client?.command("STOP") }
                    update { current.copy(active = false, connected = false, preparing = false, message = "Disconnected") }
                    stopSelf()
                }
            }
            PLAY, PAUSE -> {
                playbackRequested = intent.action == PLAY
                remote { it.command(if (intent.action == PLAY) "PLAY" else "PAUSE") }
            }
            SEEK -> remote { it.command("SEEK", intent.getLongExtra("position", 0).coerceIn(0, current.duration.coerceAtLeast(0)), current.playing) }
            VOLUME -> remote { it.setVolume(intent.getDoubleExtra("volume", current.volume)) }
            STYLE -> remote { it.setTextStyle(CastSubtitleAppearance.load(this)) }
            CONVERT -> prepareAudioWhileCasting()
            SUBTITLE -> if (current.connected && !current.preparing) scope.launch {
                try {
                    val uri = intent.getStringExtra("subtitle")
                    val text = if (uri == null) null else readSubtitle(Uri.parse(uri))
                    synchronized(resources) {
                        if (destroyed || !current.active) return@launch
                        subtitleText = text
                        subtitleUrl = text?.let { server?.setSubtitles(it) }
                        update { current.copy(subtitle = subtitleUrl != null, error = null) }
                        loadCurrent()
                    }
                } catch (e: Exception) { val message = e.message ?: "Could not load subtitles"; update { current.copy(message = message, error = message) } }
            }
        }
        return START_NOT_STICKY
    }

    private fun remote(block: (CastConnection) -> Unit) {
        scope.launch { try { connection?.let(block) } catch (e: Exception) { failure(e.message ?: "The receiver did not respond") } }
    }

    private fun loadCurrent() = synchronized(resources) {
        if (!destroyed && current.active) {
            bufferingSinceMs = 0L
            bufferingAtPositionMs = -1L
            bufferingWarningIssued = false
            val snapshot = current
            val position = snapshot.position + if (snapshot.playing) SystemClock.elapsedRealtime() - snapshot.positionUpdatedAt else 0
            connection?.load(mediaUrl, mediaMime, title, position, subtitleUrl, playbackRequested, snapshot.duration, CastSubtitleAppearance.load(this))
        }
    }

    private fun prepareAudioWhileCasting() {
        if (!current.connected || current.preparing || conversionJob?.isActive == true) return
        update { current.copy(preparing = true, message = "Converting audio for TV…", error = null) }
        conversionJob = scope.launch {
            val ctx = currentCoroutineContext()
            var candidate: CastFileServer? = null
            var attempt: File? = null
            var retained = false
            val preparationStartMs = SystemClock.elapsedRealtime()
            try {
                Log.i("VLCPlus/CastPerf", "phase=live-conversion-start receiverConnected=${current.connected} playback=${current.playing}")
                val root = requireNotNull(directory)
                val folder = File(root, "audio-${UUID.randomUUID()}").apply { check(mkdirs()) }; attempt = folder
                val prepared = CastPreparedCache.prepare(this@DirectCastService, Uri.parse(current.mediaUri), folder,
                    { ctx.ensureActive() }, { update { if (current.active) current.copy(message = it) else current } })
                ctx.ensureActive()
                Log.i("VLCPlus/CastPerf", "phase=live-conversion-ready elapsedMs=${SystemClock.elapsedRealtime() - preparationStartMs} outputBytes=${prepared.file.length()} format=${prepared.mime}")
                val http = CastFileServer(requireNotNull(localAddress), prepared.file, prepared.mime)
                candidate = http
                synchronized(resources) {
                    if (destroyed || !ctx.isActive || !current.active) return@launch
                    val captions = subtitleText?.let { http.setSubtitles(it) }
                    server?.let { retiredServers.add(it to preparedFolder) }; server = http; preparedFolder = folder
                    preparedOutput = prepared
                    candidate = null; retained = true
                    mediaUrl = http.mediaUrl(); mediaMime = prepared.mime; subtitleUrl = captions
                    val now = SystemClock.elapsedRealtime()
                    convertedUrlAwaitingPlayback = mediaUrl
                    conversionHandoffAtMs = now
                    Log.i("VLCPlus/CastPerf", "phase=live-conversion-handoff elapsedMs=${now - preparationStartMs} autoPlay=$playbackRequested")
                    update { current.copy(preparing = false, message = "Switching to compatible audio…",
                        position = current.position + if (current.playing) now - current.positionUpdatedAt else 0, positionUpdatedAt = now) }
                    loadCurrent()
                }
            } catch (_: CancellationException) { }
            catch (e: Exception) {
                Log.w("VLCPlus/CastPerf", "phase=live-conversion-failed elapsedMs=${SystemClock.elapsedRealtime() - preparationStartMs} reason=${e.javaClass.simpleName}")
                val message = e.message ?: "Audio conversion failed"
                update { current.copy(preparing = false, message = message, error = message) }
            } finally { candidate?.close(); if (!retained) attempt?.deleteRecursively() }
        }
    }

    private fun receiveStatus(data: JSONObject) {
        if (!current.active) return
        val playing = data.optString("playerState")
        // BUFFERING is a receiver state, not a pause request. Keep the intended
        // autoplay behavior for a delayed conversion handoff or subtitle reload.
        playbackRequested = CastSessionPolicy.playbackIntent(playing, playbackRequested, current.preparing)
        val idle = data.optString("idleReason")
        if (idle == "ERROR") { failure("The receiver cannot play this file. Tap Convert to prepare compatible audio, or use VLC casting."); return }
        if (playing == "IDLE") {
            update { current.copy(active = false, connected = false, preparing = false,
                message = if (idle == "FINISHED") "Playback finished" else "Playback stopped on $device") }
            stopSelf(); return
        }
        val duration = data.optJSONObject("media")?.optDouble("duration", 0.0)?.let { (it * 1000).toLong() } ?: current.duration
        synchronized(resources) {
            if (data.optJSONObject("media")?.optString("contentId") == mediaUrl) {
                retiredServers.forEach { (http, folder) -> http.close(); folder?.deleteRecursively() }; retiredServers.clear()
            }
        }
        val now = SystemClock.elapsedRealtime()
        val pendingConvertedUrl = convertedUrlAwaitingPlayback
        if (pendingConvertedUrl != null && data.optJSONObject("media")?.optString("contentId") == pendingConvertedUrl && playing == "PLAYING") {
            Log.i("VLCPlus/CastPerf", "phase=live-conversion-receiver-playing handoffMs=${now - conversionHandoffAtMs}")
            convertedUrlAwaitingPlayback = null
        }
        val reportedPosition = (data.optDouble("currentTime", current.position / 1000.0) * 1000).toLong()
        var stalledReason: String? = null
        if (playing == "BUFFERING" && data.has("mediaSessionId") && !current.preparing) {
            if (bufferingSinceMs == 0L || kotlin.math.abs(reportedPosition - bufferingAtPositionMs) > 1500) {
                bufferingSinceMs = now
                bufferingAtPositionMs = reportedPosition
                bufferingWarningIssued = false
            }
            if (!bufferingWarningIssued && now - bufferingSinceMs >= 25000L) {
                bufferingWarningIssued = true
                val http = server
                val gets = http?.mediaGetCount() ?: 0L
                val heads = http?.mediaHeadCount() ?: 0L
                val bytes = http?.mediaBytesServed() ?: 0L
                val errors = http?.mediaFailureCount() ?: 0L
                Log.w("VLCPlus/CastDiag",
                    "TV buffering timeout: mediaGet=$gets mediaHead=$heads deliveredBytes=$bytes httpErrors=$errors mime=$mediaMime")
                stalledReason = when {
                    gets == 0L -> "The TV accepted the cast, but has not requested the movie from your phone. Check that both devices are on the same local network and that the router is not isolating the TV."
                    bytes == 0L -> "The TV requested the movie, but VLC Plus could not deliver any media bytes. Check the TV's network connection and router isolation. Try casting a small MP4."
                    else -> "The TV received video data but did not start playing. It may not support this movie's video, audio or MKV container. Stop casting, choose Convert before selecting the TV, and try again. If it still buffers, try a small H.264 MP4 to distinguish codec support from a network problem."
                }
            }
        } else if (playing == "PLAYING") {
            bufferingSinceMs = 0L
            bufferingAtPositionMs = -1L
            bufferingWarningIssued = false
        }
        update { current.copy(connected = data.has("mediaSessionId"), playing = playing == "PLAYING",
            position = reportedPosition,
            positionUpdatedAt = now,
            duration = if (duration > 0) duration else current.duration,
            error = stalledReason ?: if (playing == "PLAYING") null else current.error,
            message = if (current.preparing) current.message else when (playing) {
                "PLAYING" -> "Playing on $device"
                "PAUSED" -> "Paused on $device"
                "BUFFERING" -> if (bufferingWarningIssued) "TV playback stalled — check casting diagnosis" else "Buffering on $device…"
                else -> current.message
            }) }
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

    @Synchronized private fun update(start: Boolean = false, transform: () -> DirectCastState) {
        if (destroyed) return
        val next = states.update(start) { transform() } ?: return
        state.postValue(next)
        if (next.active) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION, notification())
    }

    private fun failure(message: String) {
        convertedUrlAwaitingPlayback = null
        Log.w("VLCPlus/Cast", message)
        // A failed conversion/LOAD must not leave the receiver app attempting to
        // play an orphaned local HTTP URL after this service shuts down.
        runCatching { connection?.command("STOP") }
        update { current.copy(active = false, connected = false, preparing = false, message = message, error = message) }
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(resources) {
            destroyed = true
            convertedUrlAwaitingPlayback = null
            // End the receiver's media session before its HTTP stream disappears.
            runCatching { connection?.command("STOP") }
            connection?.close(); server?.close()
            CastPreparedCache.remember(preparedOutput)
            preparedOutput = null
            retiredServers.forEach { (http, folder) -> http.close(); folder?.deleteRecursively() }; retiredServers.clear()
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
        const val STYLE = "vlcplus.cast.STYLE"
        const val VOLUME = "vlcplus.cast.VOLUME"
        private const val CHANNEL = "vlcplus_direct_cast"
        private const val NOTIFICATION = 7319
    }
}
