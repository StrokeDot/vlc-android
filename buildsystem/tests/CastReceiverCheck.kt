package org.videolan.vlc.cast

import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val keys=KeyStore.getInstance("PKCS12")
    FileInputStream(args[0]).use { keys.load(it,"cast-test-only".toCharArray()) }
    val managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    managers.init(keys,"cast-test-only".toCharArray())
    val tls=SSLContext.getInstance("TLS").apply { init(managers.keyManagers,null,null) }
    val address=InetAddress.getLoopbackAddress()
    check(address.isLoopbackAddress)
    for(takeover in listOf(false,true)) {
        val server=tls.serverSocketFactory.createServerSocket(0,5,address) as SSLServerSocket
        server.soTimeout=15000
        val playing=CountDownLatch(1); val ended=CountDownLatch(1); val styleRejected=CountDownLatch(2)
        var failure:Throwable?=null
        lateinit var client:CastConnection
        val receiver=thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout=15000
                    fun read()=JSONObject(CastFrame.read(socket.getInputStream()).payload)
                    fun send(ns:String,data:JSONObject)=CastFrame.write(socket.getOutputStream(),"receiver-0","sender",ns,data.toString())
                    check(read().getString("type")=="CONNECT")
                    check(read().getString("type")=="GET_STATUS")
                    val apps=JSONArray().put(JSONObject().put("appId",CastConnection.DEFAULT_APP).put("transportId","transport-test").put("sessionId","session-test"))
                    send(CastConnection.RECEIVER,JSONObject().put("type","RECEIVER_STATUS").put("status",JSONObject().put("applications",apps)))
                    check(read().getString("type")=="CONNECT")
                    val load=read()
                    check(load.getString("type")=="LOAD")
                    check(load.getJSONArray("activeTrackIds").getInt(0)==1)
                    val media=load.getJSONObject("media")
                    check(media.getString("contentType")=="video/mp4")
                    check(media.getDouble("duration")==120.0)
                    check(media.getJSONObject("textTrackStyle").getString("foregroundColor")=="#FFFF00FF")
                    check(media.getJSONObject("textTrackStyle").getDouble("fontScale")==1.5)
                    check(media.getJSONArray("tracks").getJSONObject(0).getString("trackContentType")=="text/vtt")
                    check(media.getJSONArray("tracks").getJSONObject(0).getString("trackContentId")=="http://phone/subtitles.vtt")
                    send(CastConnection.MEDIA,JSONObject().put("type","MEDIA_STATUS").put("requestId",load.getInt("requestId")).put("status",JSONArray().put(JSONObject().put("mediaSessionId",42).put("playerState","PLAYING").put("currentTime",5).put("media",media))))
                    if(takeover) {
                        send(CastConnection.RECEIVER,JSONObject().put("type","RECEIVER_STATUS").put("status",JSONObject().put("applications",JSONArray().put(JSONObject().put("appId","OTHER_APP")))))
                        check(socket.getInputStream().read()==-1) { "Client relaunched instead of releasing receiver" }
                    } else {
                        val appearance=read()
                        check(appearance.getString("type")=="EDIT_TRACKS_INFO") { "Style change must not reload the movie" }
                        check(appearance.getLong("mediaSessionId")==42L)
                        check(appearance.getJSONObject("textTrackStyle").getString("fontGenericFamily")=="SERIF")
                        val newer=read()
                        check(newer.getString("type")=="EDIT_TRACKS_INFO")
                        send(CastConnection.MEDIA,JSONObject().put("type","INVALID_REQUEST").put("requestId",appearance.getInt("requestId")))
                        send(CastConnection.MEDIA,JSONObject().put("type","INVALID_REQUEST").put("requestId",newer.getInt("requestId")))
                        val volume=read()
                        check(volume.getString("type")=="SET_VOLUME")
                        check(volume.getJSONObject("volume").getDouble("level")==0.65)
                        check(!volume.getJSONObject("volume").getBoolean("muted"))
                        val seek=read()
                        check(seek.getString("type")=="SEEK")
                        check(seek.getLong("mediaSessionId")==42L)
                        check(seek.getDouble("currentTime")==20.0)
                        send(CastConnection.MEDIA,JSONObject().put("type","MEDIA_STATUS").put("status",JSONArray()))
                        check(socket.getInputStream().read()==-1)
                    }
                }
            } catch(e:Throwable) { failure=e; ended.countDown(); playing.countDown() }
        }
        client=CastConnection(address,server.localPort,
            ready={client.load("http://phone/video.mp4","video/mp4","Test",0,"http://phone/subtitles.vtt", true, 120000, CastSubtitleStyle(0,1.5,1,1,0,false,false))},
            status={if(it.optString("playerState")=="PLAYING")playing.countDown() else if(it.optString("playerState")=="IDLE")ended.countDown()},
            failed={failure=IllegalStateException(it); playing.countDown();ended.countDown()},
            styleFailed={styleRejected.countDown()})
        client.start()
        check(playing.await(15,TimeUnit.SECONDS))
        if(!takeover) {
            client.setTextStyle(CastSubtitleStyle(1,1.0,0,1,0,false,false))
            client.setTextStyle(CastSubtitleStyle(2,1.25,1,1,0,false,false))
            check(styleRejected.await(5,TimeUnit.SECONDS)) { "Receiver style rejection must be nonfatal" }
            client.setVolume(0.65); client.command("SEEK",20000,true) }
        check(ended.await(15,TimeUnit.SECONDS))
        receiver.join(15000); client.close();server.close()
        failure?.let{throw it}
        check(!receiver.isAlive)
    }
    println("TLS receiver integration: styled LOAD, style update without reload, nonfatal style rejection, volume, seek, stop and takeover passed")
}
