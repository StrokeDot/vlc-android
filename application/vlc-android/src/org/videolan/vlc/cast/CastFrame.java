/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded Cast V2 protobuf envelope; independent of Android and protobuf runtimes. */
public final class CastFrame {
    public final String namespace, payload;
    private static final int MAX_FRAME = 1024 * 1024;
    private CastFrame(String namespace, String payload) { this.namespace=namespace; this.payload=payload; }
    public static byte[] encode(String source,String destination,String namespace,String json) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(8);out.write(0);
        string(out,2,source);string(out,3,destination);string(out,4,namespace);
        out.write(40);out.write(0);string(out,6,json);
        if(out.size()>MAX_FRAME)throw new IOException("Cast message too large");
        return out.toByteArray();
    }
    private static void string(ByteArrayOutputStream out,int field,String text) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);out.write((field<<3)|2);
        int n=bytes.length;while(n>127){out.write((n&127)|128);n>>>=7;}out.write(n);
        out.write(bytes,0,bytes.length);
    }
    public static void write(OutputStream output,String source,String dest,String ns,String json) throws IOException {
        byte[] bytes=encode(source,dest,ns,json);DataOutputStream out=new DataOutputStream(output);
        out.writeInt(bytes.length);out.write(bytes);out.flush();
    }
    public static CastFrame read(InputStream input) throws IOException {
        DataInputStream in=new DataInputStream(input);int n=in.readInt();
        if(n<=0||n>MAX_FRAME)throw new IOException("Invalid Cast frame length");
        byte[] bytes=new byte[n];in.readFully(bytes);return decode(bytes);
    }
    public static CastFrame decode(byte[] bytes) throws IOException {
        if(bytes.length>MAX_FRAME)throw new IOException("Cast frame too large");
        ByteArrayInputStream in=new ByteArrayInputStream(bytes);String ns=null,json=null;
        while(in.available()>0){
            int tag=varint(in),field=tag>>>3,wire=tag&7;
            if(field==0)throw new IOException("Invalid protobuf field");
            if(wire==0){varint(in);}
            else if(wire==2){
                int n=varint(in);if(n<0||n>in.available())throw new IOException("Truncated Cast field");
                byte[] value=new byte[n];if(in.read(value)!=n)throw new EOFException();
                if(field==4)ns=new String(value,StandardCharsets.UTF_8);
                if(field==6)json=new String(value,StandardCharsets.UTF_8);
            } else if(wire==1||wire==5){int n=wire==1?8:4;if(in.skip(n)!=n)throw new EOFException();}
            else throw new IOException("Unsupported protobuf wire type");
        }
        if(ns==null)throw new IOException("Cast namespace missing");
        return new CastFrame(ns,json==null?"{}":json);
    }
    private static int varint(InputStream in) throws IOException {
        int value=0;
        for(int shift=0;shift<35;shift+=7){
            int b=in.read();if(b<0)throw new EOFException();
            if(shift==28&&(b&0xf0)!=0)throw new IOException("Varint overflow");
            value|=(b&127)<<shift;if((b&128)==0)return value;
        }
        throw new IOException("Malformed varint");
    }
}
