/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
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
    private val styleFailed: (String) -> Unit = {}
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
                val raw = Socket().also { socket = it }
                raw.connect(InetSocketAddress(address, port), 10000)
                raw.soTimeout = 15000
                val secure = tls.socketFactory.createSocket(raw, address.hostAddress, port, true) as SSLSocket
                socket = secure
                secure.startHandshake()
                secure.soTimeout = 30000
                connect("receiver-0")
                send("receiver-0", RECEIVER, JSONObject().put("type", "GET_STATUS"))
                launch {
                    while (isActive) {
                        delay(5000)
                        val waiting = pendingLoad
                        if (waiting != null && System.nanoTime() - waiting.started > 30_000_000_000L) {
                            failed("The receiver did not start this video. Tap Convert or use VLC casting.")
                            close(); return@launch
                        }
                        send("receiver-0", HEARTBEAT, JSONObject().put("type", "PING"))
                        transport?.let { send(it, MEDIA, JSONObject().put("type", "GET_STATUS")) }
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
                                if (media?.optString("idleReason") != "ERROR" && waiting != null && media != null &&
                                    !CastSessionPolicy.belongsToLoad(waiting.url, media.optJSONObject("media")?.optString("contentId") ?: "",
                                        data.optInt("requestId", -1) == waiting.requestId, waiting.previousSession, media.optLong("mediaSessionId", -1)))
                                    continue@messages
                                when (CastSessionPolicy.media(media?.optString("playerState") ?: "",
                                    media?.optString("idleReason") ?: "", waiting != null, mediaSession != null)) {
                                    CastSessionPolicy.UPDATE -> if (media != null) {
                                        if (media.has("mediaSessionId")) mediaSession = media.getLong("mediaSessionId")
                                        pendingLoad = null; status(media)
                                    }
                                    CastSessionPolicy.ERROR -> throw IllegalStateException("The receiver cannot play this file. Tap Convert to prepare compatible audio, or use VLC casting.")
                                    CastSessionPolicy.END -> {
                                        status(media ?: JSONObject().put("playerState", "IDLE").put("idleReason", "CANCELLED"))
                                        close()
                                    }
                                }
                            }
                            "INVALID_REQUEST" -> {
                                if (synchronized(writeLock) { styleRequests.remove(data.optInt("requestId", -1)) }) {
                                    styleFailed("The TV could not apply this subtitle style.")
                                } else throw IllegalStateException("The receiver could not apply this command.")
                            }
                            "LOAD_FAILED", "LOAD_CANCELLED" -> throw IllegalStateException("The receiver could not play this file. Tap Convert to prepare compatible audio, or use VLC casting.")
                        }
                        CONNECTION -> if (data.optString("type") == "CLOSE") throw IllegalStateException("The receiver closed the casting connection")
                    }
                }
            } catch (e: Exception) {
                if (!closed) failed(e.message ?: "Casting connection failed")
            } finally { close() }
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

    private fun connect(destination: String) = send(destination, CONNECTION, JSONObject().put("type", "CONNECT").put("origin", JSONObject()).put("userAgent", "VLC Plus"))

    private fun send(destination: String, namespace: String, data: JSONObject) {
        synchronized(writeLock) {
            if (closed) return
            data.put("requestId", request.incrementAndGet())
            CastFrame.write(socket?.getOutputStream() ?: return, sender, destination, namespace, data.toString())
        }
    }

    fun load(url: String, mime: String, title: String, positionMs: Long, subtitleUrl: String?, autoplay: Boolean = true, durationMs: Long = 0, style: CastSubtitleStyle = CastSubtitleStyle()) {
        val media = JSONObject().put("contentId", url).put("contentType", mime).put("streamType", "BUFFERED")
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
        synchronized(writeLock) {
            pendingLoad = PendingLoad(url, mediaSession ?: -1, request.get() + 1, System.nanoTime())
            transport?.let { send(it, MEDIA, command) }
        }
    }

    fun command(type: String, positionMs: Long? = null, playing: Boolean = true) {
        val id = mediaSession ?: return
        val data = JSONObject().put("type", type).put("mediaSessionId", id)
        receiverSession?.let { data.put("sessionId", it) }
        if (positionMs != null) data.put("currentTime", positionMs.coerceAtLeast(0) / 1000.0)
            .put("resumeState", if (playing) "PLAYBACK_START" else "PLAYBACK_PAUSE")
        transport?.let { send(it, MEDIA, data) }
    }

    fun setTextStyle(style: CastSubtitleStyle) {
        val id = mediaSession ?: return
        synchronized(writeLock) {
            val target = transport ?: return
            styleRequests.add(request.get() + 1)
            send(target, MEDIA, JSONObject().put("type", "EDIT_TRACKS_INFO").put("mediaSessionId", id)
                .put("textTrackStyle", JSONObject(style.castStyle())))
        }
    }

    fun setVolume(level: Double) {
        send("receiver-0", RECEIVER, JSONObject().put("type", "SET_VOLUME")
            .put("volume", JSONObject().put("level", level.coerceIn(0.0, 1.0)).put("muted", false)))
    }

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) { }
        scope.cancel()
    }

    companion object {
        const val DEFAULT_APP = "CC1AD845"
        const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val MEDIA = "urn:x-cast:com.google.cast.media"
        const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    }
}
