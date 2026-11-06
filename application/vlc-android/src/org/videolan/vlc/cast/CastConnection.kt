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
    private val failed: (String) -> Unit
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
                        send("receiver-0", HEARTBEAT, JSONObject().put("type", "PING"))
                        transport?.let { send(it, MEDIA, JSONObject().put("type", "GET_STATUS")) }
                    }
                }
                while (isActive && !closed) {
                    val frame = CastFrame.read(secure.inputStream)
                    val data = JSONObject(frame.payload)
                    when (frame.namespace) {
                        HEARTBEAT -> if (data.optString("type") == "PING") send("receiver-0", HEARTBEAT, JSONObject().put("type", "PONG"))
                        RECEIVER -> receiverStatus(data)
                        MEDIA -> when (data.optString("type")) {
                            "MEDIA_STATUS" -> data.optJSONArray("status")?.optJSONObject(0)?.let {
                                if (it.has("mediaSessionId")) mediaSession = it.getLong("mediaSessionId")
                                status(it)
                            }
                            "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> throw IllegalStateException("The receiver could not play this file. Try Prepare audio for TV, or VLC casting.")
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
        if (!launching) {
            launching = true
            send("receiver-0", RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", DEFAULT_APP))
        } else if (transport != null) throw IllegalStateException("Casting ended on the receiver")
    }

    private fun connect(destination: String) = send(destination, CONNECTION, JSONObject().put("type", "CONNECT").put("origin", JSONObject()).put("userAgent", "VLC Plus"))

    private fun send(destination: String, namespace: String, data: JSONObject) {
        synchronized(writeLock) {
            if (closed) return
            data.put("requestId", request.incrementAndGet())
            CastFrame.write(socket?.getOutputStream() ?: return, sender, destination, namespace, data.toString())
        }
    }

    fun load(url: String, mime: String, title: String, positionMs: Long, subtitleUrl: String?, autoplay: Boolean = true) {
        val media = JSONObject().put("contentId", url).put("contentType", mime).put("streamType", "BUFFERED")
            .put("metadata", JSONObject().put("metadataType", 0).put("title", title))
        val command = JSONObject().put("type", "LOAD").put("media", media)
            .put("autoplay", autoplay).put("currentTime", positionMs.coerceAtLeast(0) / 1000.0)
        receiverSession?.let { command.put("sessionId", it) }
        if (subtitleUrl != null) {
            media.put("tracks", JSONArray().put(JSONObject().put("trackId", 1).put("type", "TEXT")
                .put("subtype", "SUBTITLES").put("trackContentId", subtitleUrl)
                .put("trackContentType", "text/vtt").put("language", "und").put("name", "Subtitles")))
            media.put("textTrackStyle", JSONObject().put("backgroundColor", "#00000000").put("foregroundColor", "#FFFFFFFF").put("edgeType", "OUTLINE").put("edgeColor", "#000000FF"))
            command.put("activeTrackIds", JSONArray().put(1))
        }
        transport?.let { send(it, MEDIA, command) }
    }

    fun command(type: String, positionMs: Long? = null, playing: Boolean = true) {
        val id = mediaSession ?: return
        val data = JSONObject().put("type", type).put("mediaSessionId", id)
        receiverSession?.let { data.put("sessionId", it) }
        if (positionMs != null) data.put("currentTime", positionMs.coerceAtLeast(0) / 1000.0)
            .put("resumeState", if (playing) "PLAYBACK_START" else "PLAYBACK_PAUSE")
        transport?.let { send(it, MEDIA, data) }
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
