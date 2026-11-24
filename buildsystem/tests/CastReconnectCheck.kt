package org.videolan.vlc.cast

import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val keys = KeyStore.getInstance("PKCS12")
    FileInputStream(args[0]).use { keys.load(it, "cast-test-only".toCharArray()) }
    val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    managers.init(keys, "cast-test-only".toCharArray())
    val tls = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    val address = InetAddress.getLoopbackAddress()
    check(address.isLoopbackAddress)

    for (scenario in listOf("playing", "paused", "queued-conversion", "takeover", "different-movie", "finished", "unavailable", "disconnect")) {
        val server = tls.serverSocketFactory.createServerSocket(0, 5, address) as SSLServerSocket
        server.soTimeout = 9000
        val firstStatus = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val loads = AtomicInteger()
        val updates = AtomicInteger()
        val failures = AtomicInteger()
        val recoveries = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val url = "http://phone/video.mp4"
        var client: CastConnection? = null

        fun read(socket: SSLSocket): JSONObject = JSONObject(CastFrame.read(socket.inputStream).payload)
        fun send(socket: SSLSocket, namespace: String, data: JSONObject) =
            CastFrame.write(socket.outputStream, "receiver-0", "sender", namespace, data.toString())
        fun receiver(socket: SSLSocket, takeover: Boolean = false) {
            check(read(socket).getString("type") == "CONNECT")
            check(read(socket).getString("type") == "GET_STATUS")
            val app = JSONObject().put("appId", if (takeover) "OTHER_APP" else CastConnection.DEFAULT_APP)
                .put("transportId", "transport-test").put("sessionId", "session-test")
            send(socket, CastConnection.RECEIVER, JSONObject().put("type", "RECEIVER_STATUS")
                .put("status", JSONObject().put("applications", JSONArray().put(app))))
        }
        fun media(socket: SSLSocket, content: String = url, empty: Boolean = false, request: Int = -1) {
            val item = JSONObject().put("mediaSessionId", 42).put("playerState", if (scenario == "paused") "PAUSED" else "PLAYING")
                .put("currentTime", 125).put("media", JSONObject().put("contentId", content))
            send(socket, CastConnection.MEDIA, JSONObject().put("type", "MEDIA_STATUS").put("requestId", request)
                .put("status", if (empty) JSONArray() else JSONArray().put(item)))
        }

        val receiver = thread(name = "receiver-$scenario") {
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 9000
                    receiver(socket)
                    check(read(socket).getString("type") == "CONNECT")
                    check(read(socket).getString("type") == "LOAD")
                    media(socket)
                    check(firstStatus.await(3, TimeUnit.SECONDS))
                    // Force the control channel to break after stable movie playback.
                }
                if (scenario == "unavailable") {
                    server.close()
                    return@thread
                }
                if (scenario == "disconnect") {
                    client!!.close()
                    server.soTimeout = 2200
                    try {
                        server.accept().close()
                        error("Explicit disconnect reconnected")
                    } catch (_: java.net.SocketTimeoutException) { }
                    finished.countDown()
                    return@thread
                }
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 9000
                    receiver(socket, scenario == "takeover")
                    if (scenario != "takeover") {
                        check(read(socket).getString("type") == "CONNECT")
                        val query = read(socket)
                        check(query.getString("type") == "GET_STATUS") { "Recovery reloaded the video: $query" }
                        media(socket, if (scenario == "different-movie") "http://other/movie.mp4" else url,
                            scenario == "finished")
                        if (scenario == "queued-conversion") {
                            val converted = read(socket)
                            check(converted.getString("type") == "LOAD")
                            check(converted.getJSONObject("media").getString("contentId") == "http://phone/converted.mp4")
                            check(!converted.getBoolean("autoplay"))
                            check(converted.getDouble("currentTime") == 125.0)
                            media(socket, "http://phone/converted.mp4", request = converted.getInt("requestId"))
                        }
                        if (scenario in listOf("playing", "paused", "queued-conversion")) {
                            val pause = read(socket)
                            check(pause.getString("type") == "PAUSE")
                            check(pause.getLong("mediaSessionId") == 42L)
                            media(socket, empty = true)
                        }
                    }
                    check(socket.inputStream.read() == -1) { "Control socket remained open after session ended" }
                }
            } catch (e: Throwable) {
                failure.set(e)
                finished.countDown()
            }
        }

        client = CastConnection(address, server.localPort,
            ready = {
                loads.incrementAndGet()
                client!!.load(url, "video/mp4", "Test", 120000, null)
            },
            status = {
                if (it.optString("playerState") == "IDLE") {
                    finished.countDown()
                } else {
                    check(it.getDouble("currentTime") == 125.0)
                    val count = updates.incrementAndGet()
                    if (count == 1) firstStatus.countDown()
                    else if (scenario != "queued-conversion" || count == 3) client!!.command("PAUSE")
                }
            },
            reconnecting = { _, _ ->
                recoveries.incrementAndGet()
                if (scenario == "queued-conversion")
                    client!!.load("http://phone/converted.mp4", "video/mp4", "Converted", 125000, null, false)
            },
            failed = {
                failures.incrementAndGet()
                if (scenario != "unavailable") failure.set(IllegalStateException(it))
                finished.countDown()
            })
        try {
            client.start()
            check(finished.await(50, TimeUnit.SECONDS)) { "$scenario did not finish" }
            receiver.join(9500)
            failure.get()?.let { throw it }
            check(!receiver.isAlive) { "$scenario receiver did not finish" }
            check(loads.get() == 1) { "$scenario reloaded the video" }
            check(failures.get() == if (scenario == "unavailable") 1 else 0)
            check(updates.get() == when (scenario) {
                "queued-conversion" -> 3
                "playing", "paused" -> 2
                else -> 1
            })
            if (scenario == "unavailable") check(recoveries.get() == 6)
            println("Recovery passed: $scenario")
        } finally {
            client.close()
            server.close()
        }
    }
}
