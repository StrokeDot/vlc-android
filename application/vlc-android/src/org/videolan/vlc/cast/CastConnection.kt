/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Cast V2 control channel, independent of LibVLC's stream conversion pipeline. */
internal class CastConnection(
    private val address: InetAddress,
    private val port: Int,
    private val ready: () -> Unit,
    private val status: (JSONObject) -> Unit,
    private val failed: (String) -> Unit,
    private val volumeChanged: (Double) -> Unit = {},
    private val styleFailed: (String) -> Unit = {},
    private val reconnecting: (Int, IOException) -> Unit = { _, _ -> },
    /** Non-fatal: the receiver rejected one command but playback continues. */
    private val notice: (String) -> Unit = {},
    /**
     * The receiver could not play [url] (rejected, failed or errored). Return true when the caller
     * will load something else (e.g. full conversion after a streaming attempt) so the session stays up.
     */
    private val mediaError: (url: String) -> Boolean = { false }
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val request = AtomicInteger()
    private val sender = "sender-vlcplus-${UUID.randomUUID()}"
    private val writeLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    @Volatile private var transport: String? = null
    @Volatile private var mediaSession: Long? = null
    private var receiverSession: String? = null
    private var launching = false
    @Volatile private var recovering = false
    private var recoveryAttempts = 0
    private var activeMediaUrl: String? = null
    private var deferredLoad: (() -> Unit)? = null // Protected by writeLock.
    // Latest play/pause/seek and volume pressed while reconnecting; replayed once the session is back,
    // so the controller stays usable instead of graying out during a Wi-Fi hiccup.
    private var deferredCommand: (() -> Unit)? = null // Protected by writeLock.
    private var deferredVolume: Double? = null // Protected by writeLock.
    private data class PendingLoad(val url: String, val previousSession: Long, val requestId: Int, val started: Long)
    @Volatile private var pendingLoad: PendingLoad? = null
    private val styleRequests = HashSet<Int>() // Protected by writeLock; style rejection never ends playback.

    fun start() {
        scope.launch {
            try {
                require(address.isSiteLocalAddress || address.isLinkLocalAddress) { "Choose a receiver on the local network" }
                require(port in 1..65535)
                // Cast receivers use device-issued certificates, not public Web PKI.
                // This exception is confined to the explicitly selected local receiver.
                val trust = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
                    override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
                }
                val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
                while (isActive && !closed) {
                    try {
                        receive(tls)
                        break
                    } catch (e: IOException) {
                        if (closed || !isActive) break
                        if (mediaSession == null || pendingLoad != null || recoveryAttempts >= MAX_RECOVERY) throw e
                        recovering = true
                        recoveryAttempts++
                        synchronized(writeLock) { styleRequests.clear() }
                        reconnecting(recoveryAttempts, e)
                        delay((1000L shl (recoveryAttempts - 1)).coerceAtMost(8000L))
                    }
                }
            } catch (e: Exception) {
                if (!closed && e !is CancellationException)
                    failed(if (recovering) "Lost connection to the TV. Select it again to resume casting."
                        else e.message ?: "Casting connection failed")
            } finally { close() }
        }
    }

    private suspend fun receive(tls: SSLContext) = coroutineScope {
        var heartbeat: Job? = null
        try {
            ensureActive()
            val raw = Socket()
            socket = raw
            // close() may race with a reconnect while a user disconnects.
            if (closed) return@coroutineScope
            raw.connect(InetSocketAddress(address, port), if (recovering) 5000 else 10000)
            raw.soTimeout = if (recovering) 5000 else 15000
            val secure = tls.socketFactory.createSocket(raw, address.hostAddress, port, true) as SSLSocket
            socket = secure
            if (closed) return@coroutineScope
            secure.startHandshake()
            secure.soTimeout = if (recovering) 10000 else 30000
            connect("receiver-0")
            send("receiver-0", RECEIVER, JSONObject().put("type", "GET_STATUS"))
            heartbeat = launch {
                try {
                    while (isActive) {
                        delay(5000)
                        val waiting = pendingLoad
                        if (waiting != null && System.nanoTime() - waiting.started > 30_000_000_000L) {
                            pendingLoad = null
                            if (mediaError(waiting.url)) { /* caller loads an alternative */ }
                            else if (mediaSession == null) {
                                failed("The receiver did not start this video. Tap Convert or use VLC casting.")
                                close()
                                return@launch
                            } else {
                                // A replacement LOAD (subtitles/converted audio) stalled; the original keeps playing.
                                notice("The TV did not switch versions in time. The previous one keeps playing.")
                            }
                        }
                        send("receiver-0", HEARTBEAT, JSONObject().put("type", "PING"))
                        if (!recovering) transport?.let { send(it, MEDIA, JSONObject().put("type", "GET_STATUS")) }
                    }
                } catch (_: IOException) {
                    // Wake the reader so the same bounded recovery handles write failures.
                    try { secure.close() } catch (_: IOException) { }
                }
            }
            messages@ while (isActive && !closed) {
                val frame = CastFrame.read(secure.inputStream)
                val data = JSONObject(frame.payload)
                when (frame.namespace) {
                    HEARTBEAT -> if (data.optString("type") == "PING") send("receiver-0", HEARTBEAT, JSONObject().put("type", "PONG"))
                    RECEIVER -> receiverStatus(data)
                    MEDIA -> when (data.optString("type")) {
                        "MEDIA_STATUS" -> {
                            synchronized(writeLock) { styleRequests.remove(data.optInt("requestId", -1)) }
                            val media = data.optJSONArray("status")?.optJSONObject(0)
                            val waiting = pendingLoad
                            if (recovering && media != null &&
                                (media.optLong("mediaSessionId", -1) != mediaSession ||
                                    media.optJSONObject("media")?.optString("contentId")?.let { it.isNotEmpty() && it != activeMediaUrl } == true)) {
                                endSession()
                                return@coroutineScope
                            }
                            if (media?.optString("idleReason") != "ERROR" && waiting != null && media != null &&
                                !CastSessionPolicy.belongsToLoad(waiting.url, media.optJSONObject("media")?.optString("contentId") ?: "",
                                    data.optInt("requestId", -1) == waiting.requestId, waiting.previousSession, media.optLong("mediaSessionId", -1)))
                                continue@messages
                            when (CastSessionPolicy.media(media?.optString("playerState") ?: "",
                                media?.optString("idleReason") ?: "", waiting != null, mediaSession != null)) {
                                CastSessionPolicy.UPDATE -> if (media != null) {
                                    var replay: (() -> Unit)? = null
                                    var volume: Double? = null
                                    val nextLoad = synchronized(writeLock) {
                                        if (media.has("mediaSessionId")) mediaSession = media.getLong("mediaSessionId")
                                        activeMediaUrl = waiting?.url ?: activeMediaUrl
                                        recovering = false
                                        recoveryAttempts = 0
                                        pendingLoad = null
                                        replay = deferredCommand; deferredCommand = null
                                        volume = deferredVolume; deferredVolume = null
                                        deferredLoad.also { deferredLoad = null }
                                    }
                                    secure.soTimeout = 30000
                                    status(media)
                                    if (nextLoad != null) nextLoad() else replay?.invoke()
                                    volume?.let { setVolume(it) }
                                }
                                CastSessionPolicy.ERROR -> {
                                    val failedUrl = waiting?.url ?: media?.optJSONObject("media")?.optString("contentId")?.takeIf { it.isNotEmpty() } ?: activeMediaUrl ?: ""
                                    pendingLoad = null
                                    if (!mediaError(failedUrl)) throw IllegalStateException("The receiver cannot play this file. Tap Convert to prepare compatible audio, or use VLC casting.")
                                }
                                CastSessionPolicy.END -> {
                                    status(media ?: JSONObject().put("playerState", "IDLE").put("idleReason", "CANCELLED"))
                                    close()
                                }
                            }
                        }
                        "INVALID_REQUEST" -> {
                            val id = data.optInt("requestId", -1)
                            when {
                                synchronized(writeLock) { styleRequests.remove(id) } -> styleFailed("The TV could not apply this subtitle style.")
                                // Only a rejected first LOAD ends the session. A rejected play/pause/seek (common
                                // while buffering) used to kill the controller while the TV kept playing.
                                pendingLoad?.requestId == id && mediaSession == null -> {
                                    val url = pendingLoad?.url ?: ""
                                    pendingLoad = null
                                    if (!mediaError(url)) throw IllegalStateException("The receiver could not play this file. Tap Convert to prepare compatible audio, or use VLC casting.")
                                }
                                else -> { notice("The TV ignored that command. Try again."); requestStatus() }
                            }
                        }
                        "LOAD_FAILED", "LOAD_CANCELLED" -> {
                            // A newer LOAD (subtitle change, audio switch) cancels the older one; that is expected.
                            val waiting = pendingLoad
                            val id = data.optInt("requestId", -1)
                            if (waiting != null && (id == waiting.requestId || id < 0)) {
                                pendingLoad = null
                                if (mediaError(waiting.url)) Unit
                                else if (mediaSession == null) throw IllegalStateException("The receiver could not play this file. Tap Convert to prepare compatible audio, or use VLC casting.")
                                else {
                                    notice("The TV could not switch to the new version. The previous one keeps playing.")
                                    requestStatus()
                                }
                            }
                        }
                    }
                    CONNECTION -> if (data.optString("type") == "CLOSE") throw IllegalStateException("The receiver closed the casting connection")
                }
            }
        } finally {
            heartbeat?.cancel()
            try { socket?.close() } catch (_: IOException) { }
            socket = null
        }
    }

    private fun receiverStatus(data: JSONObject) {
        if (data.optString("type") == "LAUNCH_ERROR") throw IllegalStateException("The receiver could not start its media player")
        data.optJSONObject("status")?.optJSONObject("volume")?.let {
            volumeChanged(if (it.optBoolean("muted", false)) 0.0 else it.optDouble("level", 1.0))
        }
        val apps = data.optJSONObject("status")?.optJSONArray("applications") ?: JSONArray()
        for (i in 0 until apps.length()) {
            val app = apps.getJSONObject(i)
            if (app.optString("appId") != DEFAULT_APP) continue
            val id = app.optString("transportId")
            if (recovering) {
                if (id != transport || app.optString("sessionId") != receiverSession) {
                    endSession()
                } else {
                    connect(id)
                    send(id, MEDIA, JSONObject().put("type", "GET_STATUS"))
                }
                return
            }
            if (id.isNotEmpty() && id != transport) {
                transport = id
                receiverSession = app.optString("sessionId")
                connect(id)
                ready()
            }
            return
        }
        when (CastSessionPolicy.receiverGone(transport != null, launching)) {
            CastSessionPolicy.END -> {
                status(JSONObject().put("playerState", "IDLE").put("idleReason", "CANCELLED")); close()
            }
            CastSessionPolicy.LAUNCH -> {
                launching = true
                send("receiver-0", RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", DEFAULT_APP))
            }
        }
    }

    private fun endSession() {
        status(JSONObject().put("playerState", "IDLE").put("idleReason", "CANCELLED"))
        close()
    }

    private fun connect(destination: String) = send(destination, CONNECTION, JSONObject().put("type", "CONNECT").put("origin", JSONObject()).put("userAgent", "VLC Plus"))

    private fun send(destination: String, namespace: String, data: JSONObject) {
        synchronized(writeLock) {
            if (closed) return
            data.put("requestId", request.incrementAndGet())
            try {
                CastFrame.write(socket?.getOutputStream() ?: return, sender, destination, namespace, data.toString())
            } catch (e: IOException) {
                if (mediaSession == null || pendingLoad != null) throw e
                // Closing the failed channel transfers recovery to the reader.
                try { socket?.close() } catch (_: IOException) { }
            }
        }
    }

    fun load(url: String, mime: String, title: String, positionMs: Long, subtitleUrl: String?, autoplay: Boolean = true, durationMs: Long = 0, style: CastSubtitleStyle = CastSubtitleStyle()): Unit = synchronized(writeLock) {
        if (closed) return
        if (recovering) {
            // A conversion may finish while the control channel reconnects.
            deferredLoad = { load(url, mime, title, positionMs, subtitleUrl, autoplay, durationMs, style) }
            return
        }
        val media = JSONObject().put("contentId", url).put("contentType", mime).put("streamType", "BUFFERED")
        // Streaming conversion: HLS with CMAF (fMP4) audio and video segments.
        if (mime == HLS_MIME) media.put("hlsSegmentFormat", "fmp4").put("hlsVideoSegmentFormat", "fmp4")
            .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
        if (durationMs > 0) media.put("duration", durationMs / 1000.0)
        val command = JSONObject().put("type", "LOAD").put("media", media)
            .put("autoplay", autoplay).put("currentTime", positionMs.coerceAtLeast(0) / 1000.0)
        receiverSession?.let { command.put("sessionId", it) }
        if (subtitleUrl != null) {
            media.put("tracks", JSONArray().put(JSONObject().put("trackId", 1).put("type", "TEXT")
                .put("subtype", "SUBTITLES").put("trackContentId", subtitleUrl)
                .put("trackContentType", "text/vtt").put("language", "und").put("name", "Subtitles")))
            media.put("textTrackStyle", JSONObject(style.castStyle()))
            command.put("activeTrackIds", JSONArray().put(1))
        }
        pendingLoad = PendingLoad(url, mediaSession ?: -1, request.get() + 1, System.nanoTime())
        transport?.let { send(it, MEDIA, command) }
        Unit
    }

    fun command(type: String, positionMs: Long? = null, playing: Boolean = true) {
        if (recovering) {
            if (type == "STOP") close()
            else synchronized(writeLock) { deferredCommand = { command(type, positionMs, playing) } }
            return
        }
        val id = mediaSession ?: return
        val data = JSONObject().put("type", type).put("mediaSessionId", id)
        receiverSession?.let { data.put("sessionId", it) }
        if (positionMs != null) data.put("currentTime", positionMs.coerceAtLeast(0) / 1000.0)
            .put("resumeState", if (playing) "PLAYBACK_START" else "PLAYBACK_PAUSE")
        transport?.let { send(it, MEDIA, data) }
    }

    fun setTextStyle(style: CastSubtitleStyle) {
        if (recovering) return
        val id = mediaSession ?: return
        synchronized(writeLock) {
            val target = transport ?: return
            styleRequests.add(request.get() + 1)
            send(target, MEDIA, JSONObject().put("type", "EDIT_TRACKS_INFO").put("mediaSessionId", id)
                .put("textTrackStyle", JSONObject(style.castStyle())))
        }
    }

    private fun requestStatus() { transport?.let { send(it, MEDIA, JSONObject().put("type", "GET_STATUS")) } }

    /** Stops playback and closes the receiver app so the TV never keeps playing after we disconnect. */
    fun stopPlayback() {
        if (recovering) { close(); return }
        runCatching { command("STOP") }
        val session = receiverSession ?: return
        runCatching { send("receiver-0", RECEIVER, JSONObject().put("type", "STOP").put("sessionId", session)) }
    }

    fun setVolume(level: Double) {
        if (recovering) { synchronized(writeLock) { deferredVolume = level }; return }
        send("receiver-0", RECEIVER, JSONObject().put("type", "SET_VOLUME")
            .put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0)).put("muted", false)))
    }

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) { }
        scope.cancel()
    }

    companion object {
        /** ~1+2+4+8+8+8 s of back-off: survives Wi-Fi power-save gaps with the phone screen off. */
        private const val MAX_RECOVERY = 6
        const val HLS_MIME = "application/x-mpegURL"
        const val DEFAULT_APP = "CC1AD845"
        const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val MEDIA = "urn:x-cast:com.google.cast.media"
        const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    }
}
