/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

import java.nio.ByteBuffer;

/** Locate raw AAC inside one ADTS access unit before writing an MP4 sample. */
public final class CastAacSample {
    private CastAacSample() {}

    public static int headerLength(ByteBuffer data, int size) {
        if (size < 0 || size > data.limit()) throw new IllegalArgumentException("Invalid AAC sample size");
        if (size < 2 || (data.get(0) & 255) != 255 || (data.get(1) & 240) != 240) return 0;
        if (size < 7 || (data.get(1) & 6) != 0) throw new IllegalArgumentException("Invalid ADTS header");
        int header = (data.get(1) & 1) != 0 ? 7 : 9;
        int length = ((data.get(3) & 3) << 11) | ((data.get(4) & 255) << 3) | ((data.get(5) & 255) >>> 5);
        if (size <= header || length != size || (data.get(6) & 3) != 0)
            throw new IllegalArgumentException("Incomplete or unsupported ADTS access unit");
        return header;
    }
}
