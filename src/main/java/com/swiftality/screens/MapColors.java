package com.swiftality.screens;

import java.awt.Color;

/** Turns RGB into map colour ids, through a 15-bit lookup table built once. */
public final class MapColors {
    private static final int[] RGB = new int[256];
    private static final int COUNT;
    private static final byte[] LUT = new byte[1 << 15];

    static {
        int n = 4;
        for (int i = 4; i < 256; i++) {
            Color c;
            try {
                c = org.bukkit.map.MapPalette.getColor((byte) i);
            } catch (Throwable t) {
                break;
            }
            if (c == null || c.getAlpha() < 128) break;
            RGB[i] = c.getRGB() & 0xFFFFFF;
            n = i + 1;
        }
        COUNT = n;
        for (int k = 0; k < LUT.length; k++) {
            int r = ((k >> 10) & 31) * 255 / 31, g = ((k >> 5) & 31) * 255 / 31, b = (k & 31) * 255 / 31;
            LUT[k] = (byte) nearest(r, g, b);
        }
    }

    private MapColors() {}

    private static int nearest(int r, int g, int b) {
        int best = 4;
        long bestD = Long.MAX_VALUE;
        for (int i = 4; i < COUNT; i++) {
            int c = RGB[i];
            int dr = r - ((c >> 16) & 255), dg = g - ((c >> 8) & 255), db = b - (c & 255);
            // redmean-weighted distance: closer to how eyes see it than plain RGB
            long rm = (r + ((c >> 16) & 255)) / 2;
            long d = ((512 + rm) * dr * dr >> 8) + 4L * dg * dg + ((767 - rm) * db * db >> 8);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Map colour id for an 0xRRGGBB value. */
    public static byte of(int rgb) {
        return LUT[((rgb >> 9) & 0x7C00) | ((rgb >> 6) & 0x3E0) | ((rgb >> 3) & 0x1F)];
    }

    /** The RGB a map colour id is drawn with (for previews). */
    public static int rgb(byte id) {
        return RGB[id & 255];
    }

    /** ARGB pixels to map colour ids; a see-through pixel (alpha under half) becomes id 0, drawn clear. */
    public static void convert(int[] argb, byte[] out) {
        for (int i = 0; i < argb.length; i++) out[i] = (argb[i] >>> 24) < 128 ? 0 : of(argb[i]);
    }
}
