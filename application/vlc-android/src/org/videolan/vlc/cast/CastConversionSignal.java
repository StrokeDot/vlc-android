/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Input EOF precedes output cleanup. Only a subsequent natural Stop finalizes a file. */
public final class CastConversionSignal {
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile boolean ended, stopped, failed;
    public void inputEnded() { ended = true; }
    public void stopped() { stopped = true; done.countDown(); }
    public void error() { failed = true; done.countDown(); }
    public boolean await(long time, TimeUnit unit) throws InterruptedException { return done.await(time, unit); }
    public boolean successful() { return ended && stopped && !failed; }
}
