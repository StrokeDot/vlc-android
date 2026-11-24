/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WebVTT-safe cue text: keeps i/b/u, drops other tags and ASS overrides, escapes '&', '<', '>'. */
public final class CastVttText {
    private static final Pattern OVERRIDE=Pattern.compile("\\{[^}]*\\}");
    private static final Pattern ALLOWED=Pattern.compile("</?(i|b|u)>",Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG=Pattern.compile("</?[A-Za-z][^<>]*>");
    private static final Pattern ENTITY=Pattern.compile("&(amp|lt|gt|nbsp|lrm|rlm);");
    private CastVttText() {}

    /** One cue's lines: blank lines would end the cue early in WebVTT, so they are removed. */
    public static String cue(String text){
        StringBuilder out=new StringBuilder();
        for(String line:text.replace("\r\n","\n").replace('\r','\n').split("\n")){
            String clean=escape(OVERRIDE.matcher(line).replaceAll("")).trim();
            if(clean.replace(" ","").trim().isEmpty())continue;
            if(out.length()>0)out.append('\n');
            out.append(clean);
        }
        return out.toString();
    }

    public static String escape(String line){
        StringBuilder out=new StringBuilder(line.length()+8);
        Matcher allowed=ALLOWED.matcher(line),tag=TAG.matcher(line),entity=ENTITY.matcher(line);
        int i=0;
        while(i<line.length()){
            char c=line.charAt(i);
            if(c=='<'){
                if(allowed.find(i)&&allowed.start()==i){out.append(allowed.group().toLowerCase(Locale.ROOT));i=allowed.end();continue;}
                if(tag.find(i)&&tag.start()==i){i=tag.end();continue;} // <font>, <c.x>: keep the text only
                out.append("&lt;");
            }else if(c=='>')out.append("&gt;"); // also neutralises "-->" inside cue text
            else if(c=='&')out.append(entity.find(i)&&entity.start()==i?"&":"&amp;");
            else out.append(c);
            i++;
        }
        return out.toString();
    }
}
