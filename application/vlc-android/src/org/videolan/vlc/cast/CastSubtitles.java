/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

import java.util.regex.*;

public final class CastSubtitles {
    private static final String TIME="(?:(\\d{1,3}):)?(\\d{2}):(\\d{2})[,.](\\d{3})";
    private static final Pattern TIMING=Pattern.compile("^"+TIME+"\\s+-->\\s+"+TIME+"(.*)$");
    private CastSubtitles() {}
    public static String toWebVtt(String text) {
        if(text.length()>5*1024*1024)throw new IllegalArgumentException("Subtitle file is too large");
        String input=text.replace("\ufeff","").replace("\r\n","\n").replace('\r','\n');
        if(input.startsWith("WEBVTT\n")||input.startsWith("WEBVTT "))return input;
        StringBuilder out=new StringBuilder("WEBVTT\n\n");int cues=0;
        for(String block:input.split("\\n\\s*\\n")){
            String[] lines=block.trim().split("\n");int index=lines.length>0&&lines[0].trim().matches("\\d+")?1:0;
            if(index>=lines.length)continue;
            Matcher m=TIMING.matcher(lines[index].trim());
            if(!m.matches())continue;
            long start=millis(m,1),end=millis(m,5);
            if(end<=start)throw new IllegalArgumentException("Subtitle end precedes start");
            out.append(format(start)).append(" --> ").append(format(end)).append('\n');
            for(int i=index+1;i<lines.length;i++)out.append(lines[i]).append('\n');
            out.append('\n');cues++;
        }
        if(cues==0)throw new IllegalArgumentException("Choose an SRT or WebVTT subtitle file");
        return out.toString();
    }
    private static long millis(Matcher m,int n){
        long h=m.group(n)==null?0:Long.parseLong(m.group(n));
        long min=Long.parseLong(m.group(n+1)),sec=Long.parseLong(m.group(n+2));
        if(min>59||sec>59)throw new IllegalArgumentException("Invalid subtitle timestamp");
        return ((h*60+min)*60+sec)*1000+Long.parseLong(m.group(n+3));
    }
    private static String format(long ms){return String.format(java.util.Locale.ROOT,"%02d:%02d:%02d.%03d",ms/3600000,(ms/60000)%60,(ms/1000)%60,ms%1000);}
}
