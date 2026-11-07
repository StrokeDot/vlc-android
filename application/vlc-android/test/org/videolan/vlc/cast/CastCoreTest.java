package org.videolan.vlc.cast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Real JVM protocol/HTTP checks, also runnable without Android/Gradle. */
public final class CastCoreTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static void rejects(Throwing action) throws Exception {
        try { action.run(); } catch (IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection");
    }
    private interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        // Exercise browser preflights; the JDK otherwise silently drops Origin.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        check(CastSessionPolicy.receiverGone(true,false)==CastSessionPolicy.END);
        check(CastSessionPolicy.receiverGone(false,false)==CastSessionPolicy.LAUNCH);
        check(CastSessionPolicy.receiverGone(false,true)==CastSessionPolicy.IGNORE);
        check(CastSessionPolicy.media("IDLE","CANCELLED",false,true)==CastSessionPolicy.END);
        check(CastSessionPolicy.media("IDLE","CANCELLED",true,true)==CastSessionPolicy.IGNORE);
        check(CastSessionPolicy.media("","",false,true)==CastSessionPolicy.END);
        check(CastSessionPolicy.media("","",false,false)==CastSessionPolicy.IGNORE);
        check(CastSessionPolicy.media("IDLE","ERROR",true,true)==CastSessionPolicy.ERROR);
        check(CastSessionPolicy.media("PLAYING","",true,false)==CastSessionPolicy.UPDATE);
        check(!CastSessionPolicy.belongsToLoad("new","other",false,-1,1));
        check(!CastSessionPolicy.belongsToLoad("same","same",false,5,5));
        check(CastSessionPolicy.belongsToLoad("same","same",true,5,5));
        check(CastSessionPolicy.belongsToLoad("new","new",false,5,6));
        byte[] adts={(byte)0xff,(byte)0xf1,0x4c,(byte)0x80,0x01,0x7f,(byte)0xfc,1,2,3,4};
        check(CastAacSample.headerLength(java.nio.ByteBuffer.wrap(adts),adts.length)==7);
        byte[] crc={(byte)0xff,(byte)0xf0,0x4c,(byte)0x80,0x01,(byte)0xbf,(byte)0xfc,0,0,1,2,3,4};
        check(CastAacSample.headerLength(java.nio.ByteBuffer.wrap(crc),crc.length)==9);
        check(CastAacSample.headerLength(java.nio.ByteBuffer.wrap(new byte[]{(byte)0xde,2,1}),3)==0);
        rejects(()->CastAacSample.headerLength(java.nio.ByteBuffer.wrap(adts),5));
        rejects(()->CastAacSample.headerLength(java.nio.ByteBuffer.wrap(adts),10));
        byte[] multi=adts.clone();multi[6]|=1;
        rejects(()->CastAacSample.headerLength(java.nio.ByteBuffer.wrap(multi),multi.length));
        java.nio.ByteBuffer direct=java.nio.ByteBuffer.allocateDirect(adts.length);direct.put(adts);direct.flip();
        check(CastAacSample.headerLength(direct,adts.length)==7 && direct.position()==0);
        CastSubtitleStyle style = new CastSubtitleStyle(2, 1.5, 1, 2, 1, true, true);
        check(style.castStyle().get("fontGenericFamily").equals("MONOSPACED_SANS_SERIF"));
        check(style.castStyle().get("fontScale").equals(1.5));
        check(style.castStyle().get("foregroundColor").equals("#FFFF00FF"));
        check(style.castStyle().get("fontStyle").equals("BOLD_ITALIC"));
        check(style.castStyle().get("edgeType").equals("DROP_SHADOW"));
        check(style.castStyle().get("backgroundColor").equals("#000000A0"));
        style = new CastSubtitleStyle(99, Double.NaN, -1, 77, -9, false, false);
        check(style.family == 0 && style.scale == 1.0 && style.color == 0 && style.edge == 1 && style.background == 0);
        check(new CastSubtitleStyle().castStyle().get("backgroundColor").equals("#00000000"));
        CastConversionSignal signal = new CastConversionSignal();
        signal.inputEnded();
        check(!signal.await(1, java.util.concurrent.TimeUnit.MILLISECONDS));
        check(!signal.successful());
        signal.stopped();
        check(signal.await(1, java.util.concurrent.TimeUnit.SECONDS) && signal.successful());
        signal = new CastConversionSignal(); signal.stopped();
        check(signal.await(1, java.util.concurrent.TimeUnit.SECONDS) && !signal.successful());
        signal = new CastConversionSignal(); signal.inputEnded(); signal.error();
        check(signal.await(1, java.util.concurrent.TimeUnit.SECONDS) && !signal.successful());
        File growing=File.createTempFile("cast-tail", ".ts");
        java.util.concurrent.CountDownLatch observed=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.FutureTask<File> producer=new java.util.concurrent.FutureTask<>(()->{
            if(!observed.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Reader never observed completion");
            Files.write(growing.toPath(),new byte[]{9,8,7});return growing;
        });
        Thread append=new Thread(producer,"cast-final-append");append.start();
        java.util.concurrent.FutureTask<File> boundary=new java.util.concurrent.FutureTask<File>(()->null){
            @Override public boolean isDone(){observed.countDown();try{producer.get(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}return producer.isDone();}
            @Override public File get()throws InterruptedException,java.util.concurrent.ExecutionException{return producer.get();}
        };
        try(CastGrowingFile reader=new CastGrowingFile(growing,boundary,new java.util.concurrent.atomic.AtomicBoolean(),()->{})){
            byte[] tail=new byte[3];
            check(reader.readAt(0,tail,0,3)==3&&Arrays.equals(tail,new byte[]{9,8,7}));
            check(reader.readAt(3,tail,0,3)==-1);
            check(reader.readAt(0,tail,0,0)==0);
        }finally{append.join(5000);growing.delete();}
        File source=File.createTempFile("cast-source", ".ts");Files.write(source.toPath(),new byte[]{1,2,3});
        java.util.concurrent.CompletableFuture<File> complete=java.util.concurrent.CompletableFuture.completedFuture(source);
        java.util.concurrent.atomic.AtomicBoolean abort=new java.util.concurrent.atomic.AtomicBoolean();
        try(CastGrowingFile reader=new CastGrowingFile(source,complete,abort,()->{})){
            byte[] partial=new byte[5];check(reader.readAt(0,partial,0,5)==3);
            rejects(()->reader.readAt(-1,partial,0,1));abort.set(true);rejects(()->reader.readAt(0,partial,0,1));
        }finally{source.delete();}
        byte[] wire = CastFrame.encode("s", "r", "n", "{}");
        check(Arrays.equals(wire, new byte[]{8,0,18,1,115,26,1,114,34,1,110,40,0,50,2,123,125}));
        CastFrame frame = CastFrame.decode(wire);
        check(frame.namespace.equals("n") && frame.payload.equals("{}"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastFrame.write(out, "sender", "receiver", "ns", "日本語");
        check(CastFrame.read(new ByteArrayInputStream(out.toByteArray())).payload.equals("日本語"));
        rejects(() -> CastFrame.read(new ByteArrayInputStream(new byte[]{127,0,0,0})));
        rejects(() -> CastFrame.decode(new byte[]{18,127,1}));
        rejects(() -> CastFrame.read(new ByteArrayInputStream(new byte[]{0,0,0,4,1})));
        rejects(() -> CastFrame.decode(new byte[]{8,(byte)128,(byte)128,(byte)128,(byte)128,(byte)128,0}));

        String vtt = CastSubtitles.toWebVtt("\ufeff1\r\n00:00:01,200 --> 00:00:03,400\r\nHello <i>world</i>\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\n你好\r\n");
        check(vtt.startsWith("WEBVTT\n\n"));
        check(vtt.contains("00:00:01.200 --> 00:00:03.400"));
        check(vtt.contains("你好") && vtt.contains("<i>world</i>"));
        check(CastSubtitles.toWebVtt(vtt).equals(vtt));
        check(CastSubtitles.toWebVtt("00:01,000 --> 00:02,000\nHello").contains("00:00:01.000"));
        rejects(() -> CastSubtitles.toWebVtt("not a subtitle"));
        rejects(() -> CastSubtitles.toWebVtt("1\n00:00:03,000 --> 00:00:01,000\nbackwards"));

        final int[] opens={0};
        CastFileServer.Source large=new CastFileServer.Source() {
            public long length(){return 5L*1024*1024*1024;}
            public InputStream open(long offset){opens[0]++;return new ByteArrayInputStream(new byte[]{42,43,44});}
        };
        try(CastFileServer server=new CastFileServer(InetAddress.getLoopbackAddress(),large,"video/x-matroska")) {
            check(opens[0]==0);
            HttpURLConnection c=request(server.mediaUrl(),"HEAD",null);
            check(c.getResponseCode()==200&&c.getContentLengthLong()==large.length());
            check(opens[0]==0);c.disconnect();
            c=request(server.mediaUrl(),"GET","bytes=1024-1026");
            check(c.getResponseCode()==206&&Arrays.equals(c.getInputStream().readAllBytes(),new byte[]{42,43,44}));
            check(opens[0]==1);c.disconnect();
        }

        File media = File.createTempFile("cast-core", ".mp4");
        byte[] data = new byte[1024];
        for (int i=0;i<data.length;i++) data[i]=(byte)i;
        Files.write(media.toPath(), data);
        try (CastFileServer server = new CastFileServer(InetAddress.getLoopbackAddress(), media, "video/mp4")) {
            String url = server.mediaUrl();
            HttpURLConnection c = request(url, "GET", null);
            check(c.getResponseCode()==200);
            check(Arrays.equals(c.getInputStream().readAllBytes(),data)); c.disconnect();
            c=request(url,"GET","bytes=10-19");
            check(c.getResponseCode()==206 && "bytes 10-19/1024".equals(c.getHeaderField("Content-Range")));
            check(Arrays.equals(c.getInputStream().readAllBytes(),Arrays.copyOfRange(data,10,20)));c.disconnect();
            c=request(url,"GET","bytes=-5"); check(c.getResponseCode()==206);
            check(c.getInputStream().readAllBytes().length==5);c.disconnect();
            c=request(url,"GET","bytes=1000-");check(c.getResponseCode()==206);
            check(c.getInputStream().readAllBytes().length==24);c.disconnect();
            c=request(url,"HEAD",null);check(c.getResponseCode()==200);
            check(c.getContentLengthLong()==1024 && c.getInputStream().read()==-1);c.disconnect();
            for(String range:new String[]{"bytes=1024-", "bytes=20-10", "bytes=1-2,5-6", "bytes=-0", "bytes=xyz", "bytes=999999999999999999999-"}) {
                c=request(url,"GET",range);check(c.getResponseCode()==416);c.disconnect();
            }
            c=request(url,"OPTIONS",null);check(c.getResponseCode()==204);
            check("*".equals(c.getHeaderField("Access-Control-Allow-Origin")));c.disconnect();
            c=request(url,"OPTIONS",null);c.setRequestProperty("Origin","https://www.gstatic.com");
            c.setRequestProperty("Access-Control-Request-Private-Network","true");
            check(c.getResponseCode()==204);
            check("https://www.gstatic.com".equals(c.getHeaderField("Access-Control-Allow-Origin")));
            check("true".equals(c.getHeaderField("Access-Control-Allow-Private-Network")));
            check(c.getHeaderField("Access-Control-Allow-Headers").contains("Accept-Encoding"));c.disconnect();
            c=request(url.replace(server.token(),"wrong"),"GET",null);check(c.getResponseCode()==404);c.disconnect();
            String subs=server.setSubtitles(vtt);
            c=request(subs,"GET",null);check(c.getResponseCode()==200);
            check(c.getContentType().startsWith("text/vtt"));
            check(new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8).equals(vtt));c.disconnect();
            c=request(url,"POST",null);check(c.getResponseCode()==405);c.disconnect();
        } finally { media.delete(); }
        File sustained = File.createTempFile("cast-delivery", ".mp4");
        byte[] block = new byte[128 * 1024];
        new java.util.Random(817203L).nextBytes(block);
        try {
            try(OutputStream file=new FileOutputStream(sustained)) {
                for(int i=0;i<256;i++) { block[0]=(byte)i; block[1]=(byte)(i>>>8); file.write(block); }
            }
            final long[] starts={0, 3*1024*1024+17, 9*1024*1024+113};
            final byte[][] expected=new byte[3][];
            try(RandomAccessFile file=new RandomAccessFile(sustained,"r")) {
                for(int i=0;i<starts.length;i++) {
                    file.seek(starts[i]); java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                    long left=8*1024*1024;
                    while(left>0){int n=file.read(block,0,(int)Math.min(block.length,left));digest.update(block,0,n);left-=n;}
                    expected[i]=digest.digest();
                }
            }
            check(!Arrays.equals(expected[0],expected[1])&&!Arrays.equals(expected[1],expected[2]));
            java.util.concurrent.ExecutorService clients=java.util.concurrent.Executors.newFixedThreadPool(3);
            long started=System.nanoTime();
            try(CastFileServer server=new CastFileServer(InetAddress.getLoopbackAddress(),sustained,"video/mp4")) {
                String subs=server.setSubtitles(vtt);
                for(int wave=0;wave<3;wave++) {
                    java.util.List<java.util.concurrent.Future<Boolean>> transfers=new java.util.ArrayList<>();
                    for(int client=0;client<3;client++) {
                        final int index=client; final long offset=starts[client];
                        transfers.add(clients.submit(()->{
                            HttpURLConnection c=request(server.mediaUrl(),"GET","bytes="+offset+"-"+(offset+8*1024*1024-1));
                            try(InputStream input=c.getInputStream()) {
                                java.security.MessageDigest sum=java.security.MessageDigest.getInstance("SHA-256");
                                byte[] buffer=new byte[128*1024];long bytes=0;
                                for(int n;(n=input.read(buffer))>=0;){sum.update(buffer,0,n);bytes+=n;}
                                return bytes==8*1024*1024 && Arrays.equals(sum.digest(),expected[index]);
                            } finally {c.disconnect();}
                        }));
                    }
                    HttpURLConnection c=request(subs,"GET",null);
                    check(new String(c.getInputStream().readAllBytes(),StandardCharsets.UTF_8).equals(vtt));c.disconnect();
                    for(java.util.concurrent.Future<Boolean> transfer:transfers) check(transfer.get(15,java.util.concurrent.TimeUnit.SECONDS));
                }
                System.out.println("Concurrent delivery: 72 MiB plus captions in "+((System.nanoTime()-started)/1000000)+" ms (loopback only)");
            } finally {clients.shutdownNow();}
        } finally {sustained.delete();}
        System.out.println("Direct Cast core: " + checks + " checks passed");
    }
    private static HttpURLConnection request(String url,String method,String range) throws Exception {
        HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();
        c.setConnectTimeout(2000);c.setReadTimeout(2000);c.setRequestMethod(method);
        if(range!=null)c.setRequestProperty("Range",range);return c;
    }
}
