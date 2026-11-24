/* SPDX-License-Identifier: GPL-2.0-or-later */
package org.videolan.vlc.cast;

/** 5.1/7.1 to stereo, matching the original 1, 0.707, 0.5 weights and /2.5 normalisation in Q8. */
public final class CastDownmix {
    private CastDownmix() {}
    // Channel order: L R C LFE Ls Rs [Lb Rb]; LFE is dropped. 181/256 ~= 0.707, 128/256 = 0.5.
    public static void toStereo(short[] in, short[] out, int frames, int channels) {
        if (channels != 6 && channels != 8) throw new IllegalArgumentException("Unsupported layout");
        for (int f = 0, i = 0, o = 0; f < frames; f++, i += channels) {
            int c = in[i + 2] * 181;
            int left = (in[i] << 8) + c + in[i + 4] * 181;
            int right = (in[i + 1] << 8) + c + in[i + 5] * 181;
            if (channels == 8) { left += in[i + 6] << 7; right += in[i + 7] << 7; }
            out[o++] = clamp(left / 640); // 256 * 2.5
            out[o++] = clamp(right / 640);
        }
    }
    private static short clamp(int v) { return (short) (v < -32768 ? -32768 : (v > 32767 ? 32767 : v)); }
}
