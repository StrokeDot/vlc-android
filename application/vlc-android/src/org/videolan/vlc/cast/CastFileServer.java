/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Seekable, constant-memory local file delivery. Only unguessable registered URLs exist. */
public final class CastFileServer implements Closeable {
    private final ServerSocket server;
    private final File file;
    private final String mime,secret=UUID.randomUUID().toString();
    private volatile byte[] subtitles=new byte[0];
    private final Set<Socket> sockets=Collections.newSetFromMap(new ConcurrentHashMap<Socket,Boolean>());
    private final ExecutorService workers=new ThreadPoolExecutor(1,4,30,TimeUnit.SECONDS,new SynchronousQueue<Runnable>());
    private volatile boolean closed;
    public CastFileServer(InetAddress address,File file,String mime) throws IOException {
        if(!file.isFile()||file.length()==0)throw new IOException("Media file is empty or unavailable");
        this.file=file;this.mime=mime;server=new ServerSocket(0,8,address);
        Thread thread=new Thread(()->{
            while(!closed){
                try{Socket socket=server.accept();socket.setSoTimeout(15000);sockets.add(socket);
                    try{workers.execute(()->serve(socket));}catch(RejectedExecutionException e){sockets.remove(socket);socket.close();}
                }catch(IOException e){if(!closed)close();}
            }
        },"VLC-Plus-Cast-HTTP");thread.setDaemon(true);thread.start();
    }
    public String token(){return secret;}
    private String base(){String host=server.getInetAddress().getHostAddress();if(host.contains(":"))host="["+host+"]";return "http://"+host+":"+server.getLocalPort()+"/"+secret;}
    public String mediaUrl(){return base()+"/media";}
    public String setSubtitles(String vtt){subtitles=vtt.getBytes(StandardCharsets.UTF_8);return base()+"/subtitles.vtt";}
    private void serve(Socket socket){
        try(Socket ignored=socket){
            InputStream in=socket.getInputStream();String first=line(in);
            String[] request=first.split(" ");if(request.length!=3)return;
            Map<String,String> headers=new HashMap<>();int size=first.length();
            for(String s;(s=line(in)).length()>0;){size+=s.length();if(size>16384)return;int colon=s.indexOf(':');if(colon>0)headers.put(s.substring(0,colon).toLowerCase(Locale.ROOT),s.substring(colon+1).trim());}
            boolean media=request[1].equals("/"+secret+"/media"),sub=request[1].equals("/"+secret+"/subtitles.vtt");
            OutputStream out=socket.getOutputStream();
            String origin=headers.get("origin");
            if(!media&&!sub){head(out,404,"Not Found",0,null,null,origin);return;}
            if(request[0].equals("OPTIONS")){head(out,204,"No Content",0,null,null,origin);return;}
            if(!request[0].equals("GET")&&!request[0].equals("HEAD")){head(out,405,"Method Not Allowed",0,null,null,origin);return;}
            byte[] text=subtitles;long length=media?file.length():text.length,start=0,end=length-1;boolean partial=false;
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
            head(out,partial?206:200,partial?"Partial Content":"OK",count,media?mime:"text/vtt; charset=utf-8",partial?"bytes "+start+"-"+end+"/"+length:null,origin);
            if(request[0].equals("HEAD"))return;
            if(media){try(RandomAccessFile input=new RandomAccessFile(file,"r")){
                input.seek(start);byte[] buf=new byte[128*1024];long left=count;
                while(left>0&&!closed){int n=input.read(buf,0,(int)Math.min(buf.length,left));if(n<0)break;out.write(buf,0,n);left-=n;}
            }}else if(count>0)out.write(text,(int)start,(int)count);
            out.flush();
        }catch(IOException ignored){}finally{sockets.remove(socket);}
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
