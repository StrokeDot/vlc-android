package org.videolan.vlc.cast

import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** A receiver that rejects the streaming (HLS) LOAD keeps the session for the full-conversion LOAD. */
fun main(args: Array<String>) {
    val keys = KeyStore.getInstance("PKCS12")
    FileInputStream(args[0]).use { keys.load(it, "cast-test-only".toCharArray()) }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, "cast-test-only".toCharArray()) }
    val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    for (rejection in listOf("LOAD_FAILED", "ERROR_STATUS")) {
        val server = tls.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.soTimeout = 9000
        val done = CountDownLatch(1); val failure = AtomicReference<Throwable?>(); val rejected = AtomicReference<String?>()
        var client: CastConnection? = null
        fun read(s: SSLSocket) = JSONObject(CastFrame.read(s.inputStream).payload)
        fun send(s: SSLSocket, ns: String, d: JSONObject) = CastFrame.write(s.outputStream, "receiver-0", "sender", ns, d.toString())
        val receiver = thread {
            try {
                (server.accept() as SSLSocket).use { s ->
                    s.soTimeout = 9000
                    check(read(s).getString("type") == "CONNECT"); check(read(s).getString("type") == "GET_STATUS")
                    send(s, CastConnection.RECEIVER, JSONObject().put("type", "RECEIVER_STATUS").put("status", JSONObject().put("applications",
                        JSONArray().put(JSONObject().put("appId", CastConnection.DEFAULT_APP).put("transportId", "t").put("sessionId", "s")))))
                    check(read(s).getString("type") == "CONNECT")
                    val hls = read(s)
                    check(hls.getString("type") == "LOAD" && hls.getJSONObject("media").getString("hlsSegmentFormat") == "fmp4")
                    if (rejection == "LOAD_FAILED") send(s, CastConnection.MEDIA, JSONObject().put("type", "LOAD_FAILED").put("requestId", hls.getInt("requestId")))
                    else send(s, CastConnection.MEDIA, JSONObject().put("type", "MEDIA_STATUS").put("requestId", hls.getInt("requestId"))
                        .put("status", JSONArray().put(JSONObject().put("mediaSessionId", 3).put("playerState", "IDLE").put("idleReason", "ERROR"))))
                    var file = read(s)
                    while (file.getString("type") != "LOAD") file = read(s)
                    check(file.getJSONObject("media").getString("contentId") == "http://phone/full.mp4")
                    send(s, CastConnection.MEDIA, JSONObject().put("type", "MEDIA_STATUS").put("requestId", file.getInt("requestId"))
                        .put("status", JSONArray().put(JSONObject().put("mediaSessionId", 4).put("playerState", "PLAYING")
                            .put("media", JSONObject().put("contentId", "http://phone/full.mp4")))))
                    check(done.await(5, TimeUnit.SECONDS))
                }
            } catch (e: Throwable) { failure.compareAndSet(null, e); done.countDown() }
        }
        client = CastConnection(InetAddress.getLoopbackAddress(), server.localPort,
            ready = { client!!.load("http://phone/hls/master.m3u8", CastConnection.HLS_MIME, "Movie", 0, null) },
            status = { if (it.optString("playerState") == "PLAYING") done.countDown() },
            failed = { failure.compareAndSet(null, IllegalStateException("Session ended: $it")); done.countDown() },
            mediaError = { url ->
                rejected.set(url)
                thread { Thread.sleep(200); client!!.load("http://phone/full.mp4", "video/mp4", "Movie", 0, null) }
                true
            })
        try {
            client.start()
            check(done.await(20, TimeUnit.SECONDS)) { "$rejection did not finish" }
            failure.get()?.let { throw it }
            check(rejected.get() == "http://phone/hls/master.m3u8") { "rejected url ${rejected.get()}" }
            println("Fallback passed: $rejection")
        } finally { client.close(); server.close(); receiver.join(2000) }
    }
}
