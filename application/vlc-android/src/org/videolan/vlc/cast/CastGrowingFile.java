/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

import java.io.*;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Blocking random access to a growing stream, with EOF owned by the producer. */
public final class CastGrowingFile implements Closeable {
    private final RandomAccessFile input;
    private final Future<?> writer;
    private final AtomicBoolean aborted;
    private final Runnable check;
    private boolean closed;
    public CastGrowingFile(File file, Future<?> writer, AtomicBoolean aborted, Runnable check) throws IOException {
        this.input=new RandomAccessFile(file,"r");this.writer=writer;this.aborted=aborted;this.check=check;
    }
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if(size==0)return 0;
        if(position<0)throw new IllegalArgumentException("Negative position");
        for(;;) {
            try{check.run();}catch(RuntimeException e){throw new IOException("Preparation interrupted",e);}
            if(aborted.get())throw new IOException("Preparation stopped");
            synchronized(this) {
                if(closed)throw new IOException("Audio stream closed");
                long available=input.length()-position;
                boolean done=writer.isDone();
                if(done) {
                    try{writer.get();}catch(Exception e){throw new IOException("Audio preparation failed",e);}
                    // The final append may precede completion but follow length().
                    available=input.length()-position;
                }
                if(available>=size||done) {
                    if(available<=0)return -1;
                    input.seek(position);return input.read(buffer,offset,(int)Math.min(size,available));
                }
            }
            try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Preparation interrupted",e);}
        }
    }
    @Override public synchronized void close() throws IOException {if(!closed){closed=true;input.close();}}
}
