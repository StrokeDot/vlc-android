/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Seekable, constant-memory local file delivery. Only unguessable registered URLs exist. */
public final class CastFileServer implements Closeable {
    private final ServerSocket server;
    public interface Source {
        long length();
        InputStream open(long offset) throws IOException;
    }
    /** A stream backed by a file descriptor; lets the kernel send pages straight to the socket. */
    public interface Channelled {
        FileChannel channel();
        /** Absolute channel position that corresponds to the stream's current offset. */
        long position();
    }
    public abstract static class ChannelledStream extends InputStream implements Channelled {}
    /** A named resource served under /<token>/hls/ (playlists and on-demand segments). */
    public interface Resource {
        long length();
        String type();
        InputStream open(long offset) throws IOException;
    }
    public interface Router { Resource resolve(String name) throws IOException; }
    public static Resource bytes(final byte[] data,final String type){
        return new Resource(){
            public long length(){return data.length;}
            public String type(){return type;}
            public InputStream open(long offset){return new ByteArrayInputStream(data,(int)offset,data.length-(int)offset);}
        };
    }
    public static Resource file(final File file,final String type) throws IOException {
        final FileSource source=new FileSource(file);
        return new Resource(){
            public long length(){return source.length();}
            public String type(){return type;}
            public InputStream open(long offset)throws IOException{return source.open(offset);}
        };
    }
    private volatile Router router;
    /** Installs (or removes) HLS routes; returns the master playlist URL. */
    public String setRouter(Router value){router=value;return base()+"/hls/master.m3u8";}
    public static final class FileSource implements Source {
        private final File file;
        public FileSource(File file) throws IOException {
            if(!file.isFile()||file.length()==0)throw new IOException("Media file is empty or unavailable");
            this.file=file;
        }
        public long length(){return file.length();}
        public InputStream open(long offset) throws IOException {
            final RandomAccessFile input=new RandomAccessFile(file,"r");
            try{input.seek(offset);}catch(IOException e){input.close();throw e;}
            return new ChannelledStream(){
                public FileChannel channel(){return input.getChannel();}
                public long position(){return offset;}
                public int read()throws IOException{return input.read();}
                public int read(byte[] b,int start,int length)throws IOException{return input.read(b,start,length);}
                public void close()throws IOException{input.close();}
            };
        }
    }
    private final Source source;
    private final String mime,secret=UUID.randomUUID().toString();
    private volatile byte[] subtitles=new byte[0];
    private final AtomicLong mediaGets=new AtomicLong();
    private final AtomicLong mediaHeads=new AtomicLong();
    private final AtomicLong mediaBytes=new AtomicLong();
    private final AtomicLong mediaReadFailures=new AtomicLong();
    /** Aggregate local-only diagnostics: no file names, tokens or user URLs. */
    public long mediaGetCount(){return mediaGets.get();}
    public long mediaHeadCount(){return mediaHeads.get();}
    public long mediaBytesServed(){return mediaBytes.get();}
    public long mediaFailureCount(){return mediaReadFailures.get();}
    private final Set<Socket> sockets=Collections.newSetFromMap(new ConcurrentHashMap<Socket,Boolean>());
    // Receivers open overlapping range requests while seeking (and old ones linger until their socket
    // times out). Four workers rejected the next request, which surfaced as stalls after seeks.
    private final ExecutorService workers=new ThreadPoolExecutor(2,16,30,TimeUnit.SECONDS,new SynchronousQueue<Runnable>());
    private volatile boolean closed;
    public CastFileServer(InetAddress address,File file,String mime) throws IOException {
        this(address,new FileSource(file),mime);
    }
    public CastFileServer(InetAddress address,Source source,String mime) throws IOException {
        if(source.length()<=0)throw new IOException("Media length is unavailable");
        this.source=source;this.mime=mime;server=ServerSocketChannel.open().socket();
        try{server.bind(new InetSocketAddress(address,0),8);}catch(IOException e){server.close();throw e;}
        Thread thread=new Thread(()->{
            while(!closed){
                try{Socket socket=server.accept();socket.setSoTimeout(15000);tune(socket);sockets.add(socket);
                    try{workers.execute(()->serve(socket));}catch(RejectedExecutionException e){sockets.remove(socket);socket.close();}
                }catch(IOException e){if(!closed)close();}
            }
        },"VLC-Plus-Cast-HTTP");thread.setDaemon(true);thread.start();
    }
    private static void tune(Socket socket){
        // Headers are tiny and latency-sensitive; video needs a deep send queue on Wi-Fi.
        try{socket.setTcpNoDelay(true);}catch(IOException ignored){}
        try{socket.setSendBufferSize(1024*1024);}catch(IOException ignored){}
    }
    public String token(){return secret;}
    private String base(){String host=server.getInetAddress().getHostAddress();if(host.contains(":"))host="["+host+"]";return "http://"+host+":"+server.getLocalPort()+"/"+secret;}
    public String mediaUrl(){return base()+"/media";}
    public String setSubtitles(String vtt){subtitles=vtt.getBytes(StandardCharsets.UTF_8);return base()+"/subtitles.vtt";}
    private void serve(Socket socket){
        try(Socket ignored=socket){
            // Buffered: header parsing reads byte-by-byte, which was one syscall per byte per request.
            InputStream in=new BufferedInputStream(socket.getInputStream(),2048);String first=line(in);
            String[] request=first.split(" ");if(request.length!=3)return;
            Map<String,String> headers=new HashMap<>();int size=first.length();
            for(String s;(s=line(in)).length()>0;){size+=s.length();if(size>16384)return;int colon=s.indexOf(':');if(colon>0)headers.put(s.substring(0,colon).toLowerCase(Locale.ROOT),s.substring(colon+1).trim());}
            boolean media=request[1].equals("/"+secret+"/media"),sub=request[1].equals("/"+secret+"/subtitles.vtt");
            String hlsPrefix="/"+secret+"/hls/";Router routes=router;Resource routed=null;
            if(!media&&!sub&&routes!=null&&request[1].startsWith(hlsPrefix)&&!request[1].contains("..")){
                try{routed=routes.resolve(request[1].substring(hlsPrefix.length()));}
                catch(IOException e){OutputStream o=socket.getOutputStream();head(o,503,"Service Unavailable",0,null,null,headers.get("origin"));return;}
            }
            OutputStream out=socket.getOutputStream();
            String origin=headers.get("origin");
            if(!media&&!sub&&routed==null){head(out,404,"Not Found",0,null,null,origin);return;}
            if(request[0].equals("OPTIONS")){head(out,204,"No Content",0,null,null,origin);return;}
            if(!request[0].equals("GET")&&!request[0].equals("HEAD")){head(out,405,"Method Not Allowed",0,null,null,origin);return;}
            if(media||routed!=null){if(request[0].equals("GET"))mediaGets.incrementAndGet();else mediaHeads.incrementAndGet();}
            byte[] text=subtitles;long length=routed!=null?routed.length():media?source.length():text.length,start=0,end=length-1;boolean partial=false;
            String range=headers.get("range");
            if(range!=null){
                try{
                    if(!range.matches("bytes=\\d*-\\d*"))throw new IllegalArgumentException();
                    String[] parts=range.substring(6).split("-",-1);
                    if(parts[0].isEmpty()){long suffix=Long.parseLong(parts[1]);if(suffix<=0)throw new IllegalArgumentException();start=Math.max(0,length-suffix);}
                    else{start=Long.parseLong(parts[0]);if(!parts[1].isEmpty())end=Math.min(end,Long.parseLong(parts[1]));}
                    if(start>=length||end<start)throw new IllegalArgumentException();partial=true;
                }catch(IllegalArgumentException e){head(out,416,"Range Not Satisfiable",0,null,"bytes */"+length,origin);return;}
            }
            long count=Math.max(0,end-start+1);
            head(out,partial?206:200,partial?"Partial Content":"OK",count,routed!=null?routed.type():media?mime:"text/vtt; charset=utf-8",partial?"bytes "+start+"-"+end+"/"+length:null,origin);
            if(request[0].equals("HEAD"))return;
            if(media||routed!=null){
                long sent=0;
                try(InputStream input=routed!=null?routed.open(start):source.open(start)){
                    // Let the kernel deliver file pages directly (files and content-URI descriptors alike);
                    // avoid a Java read/copy per chunk.
                    if(input instanceof Channelled && socket.getChannel()!=null){
                        FileChannel file=((Channelled)input).channel();long base=((Channelled)input).position();
                        try{
                            while(sent<count&&!closed){
                                long n=file.transferTo(base+sent,Math.min(8L*1024*1024,count-sent),socket.getChannel());
                                if(n<=0)break; // Some providers/platforms need the portable copy path.
                                sent+=n;mediaBytes.addAndGet(n);
                            }
                        }catch(IOException e){if(sent>0||closed)throw e;} // Nothing sent yet: fall back below.
                    }
                    if(sent<count){
                        if(sent>0&&input instanceof Channelled)((Channelled)input).channel().position(((Channelled)input).position()+sent);
                        byte[] buf=new byte[256*1024];long left=count-sent;
                        while(left>0&&!closed){int n=input.read(buf,0,(int)Math.min(buf.length,left));if(n<0)break;out.write(buf,0,n);left-=n;mediaBytes.addAndGet(n);}
                    }
                }
            }else if(count>0)out.write(text,(int)start,(int)count);
            out.flush();
        }catch(IOException ignored){mediaReadFailures.incrementAndGet();}finally{sockets.remove(socket);}
    }
    private static String line(InputStream input)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
        while((c=input.read())!=-1&&c!='\n'){if(c!='\r')out.write(c);if(out.size()>8192)throw new IOException("HTTP line too long");}
        if(c==-1&&out.size()==0)throw new EOFException();return new String(out.toByteArray(),StandardCharsets.US_ASCII);
    }
    private static void head(OutputStream out,int code,String reason,long length,String type,String range,String origin)throws IOException{
        String allowed=origin!=null && (origin.equals("null") || origin.matches("https?://[A-Za-z0-9.\\-:\\[\\]_%]+"))?origin:"*";
        String h="HTTP/1.1 "+code+" "+reason+"\r\nConnection: close\r\nAccept-Ranges: bytes\r\nCache-Control: no-store\r\nVary: Origin\r\nAccess-Control-Allow-Origin: "+allowed+"\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range, Content-Type, Accept-Encoding\r\nAccess-Control-Allow-Private-Network: true\r\nAccess-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\nContent-Length: "+length+"\r\n";
        if(type!=null)h+="Content-Type: "+type+"\r\n";if(range!=null)h+="Content-Range: "+range+"\r\n";
        out.write((h+"\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();
    }
    @Override public void close(){closed=true;try{server.close();}catch(IOException ignored){}for(Socket s:sockets)try{s.close();}catch(IOException ignored){}workers.shutdownNow();}
}
