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

/** A rejected transport command or superseded LOAD must not end casting; Stop must reach the TV. */
fun main(args: Array<String>) {
    val keys = KeyStore.getInstance("PKCS12")
    FileInputStream(args[0]).use { keys.load(it, "cast-test-only".toCharArray()) }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    managers.init(keys, "cast-test-only".toCharArray())
    val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    val server = tls.serverSocketFactory.createServerSocket(0, 5, InetAddress.getLoopbackAddress()) as SSLServerSocket
    server.soTimeout = 9000
    val playing = CountDownLatch(1); val noticed = CountDownLatch(2); val resumed = CountDownLatch(1); val done = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    var client: CastConnection? = null
    fun read(s: SSLSocket) = JSONObject(CastFrame.read(s.inputStream).payload)
    var resyncs = 0
    /** Next message of [type]; status queries (resyncs) may interleave with the sender's own commands. */
    fun next(s: SSLSocket, type: String): JSONObject { while (true) { val m = read(s); if (m.getString("type") == type) return m
        check(m.getString("type") == "GET_STATUS") { "Unexpected $m" }; resyncs++ } }
    fun send(s: SSLSocket, ns: String, data: JSONObject) = CastFrame.write(s.outputStream, "receiver-0", "sender", ns, data.toString())
    fun status(s: SSLSocket, url: String, request: Int = -1) = send(s, CastConnection.MEDIA, JSONObject().put("type", "MEDIA_STATUS").put("requestId", request)
        .put("status", JSONArray().put(JSONObject().put("mediaSessionId", 7).put("playerState", "PLAYING").put("media", JSONObject().put("contentId", url)))))
    val receiver = thread {
        try {
            (server.accept() as SSLSocket).use { s ->
                s.soTimeout = 9000
                check(read(s).getString("type") == "CONNECT"); check(read(s).getString("type") == "GET_STATUS")
                send(s, CastConnection.RECEIVER, JSONObject().put("type", "RECEIVER_STATUS").put("status", JSONObject().put("applications",
                    JSONArray().put(JSONObject().put("appId", CastConnection.DEFAULT_APP).put("transportId", "t").put("sessionId", "session-1")))))
                check(read(s).getString("type") == "CONNECT")
                val load = read(s); check(load.getString("type") == "LOAD")
                status(s, "http://phone/a", load.getInt("requestId"))
                check(playing.await(3, TimeUnit.SECONDS))
                val pause = read(s); check(pause.getString("type") == "PAUSE")
                send(s, CastConnection.MEDIA, JSONObject().put("type", "INVALID_REQUEST").put("requestId", pause.getInt("requestId")))
                // A replacement LOAD (new subtitles) that the TV cancels must leave the original playing.
                val second = next(s, "LOAD")
                send(s, CastConnection.MEDIA, JSONObject().put("type", "LOAD_CANCELLED").put("requestId", second.getInt("requestId")))
                while (resyncs < 2) next(s, "GET_STATUS").also { resyncs++ } // one resync per rejection
                status(s, "http://phone/a")
                check(resumed.await(3, TimeUnit.SECONDS))
                val stop = read(s); check(stop.getString("type") == "STOP" && stop.getLong("mediaSessionId") == 7L)
                val app = read(s); check(app.getString("type") == "STOP" && app.getString("sessionId") == "session-1")
                done.countDown()
            }
        } catch (e: Throwable) { e.printStackTrace(); failure.set(e); done.countDown() }
    }
    var updates = 0
    client = CastConnection(InetAddress.getLoopbackAddress(), server.localPort,
        ready = { client!!.load("http://phone/a", "video/mp4", "A", 0, null) },
        status = { if (++updates == 1) { playing.countDown(); client!!.command("PAUSE") } else resumed.countDown() },
        failed = { failure.set(IllegalStateException("Session ended: $it")); done.countDown() },
        notice = { noticed.countDown(); if (noticed.count == 1L) client!!.load("http://phone/a", "video/mp4", "A", 0, "http://phone/sub.vtt") })
    try {
        client.start()
        check(resumed.await(15, TimeUnit.SECONDS)) { "session did not survive: ${failure.get()}" }
        client.stopPlayback()
        check(done.await(10, TimeUnit.SECONDS)) { "did not finish" }
        failure.get()?.let { throw it }
        check(noticed.count == 0L) { "notices not raised" }
        println("Command checks passed")
    } finally { client.close(); server.close(); receiver.join(2000) }
}
