package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;

public final class SmbDeviceColor {

    /** Hand-picked: every pair is distinct at badge size, on any cover, in both themes. */
    private static final int[] PALETTE = {
            0xFFE53935, // red
            0xFFD81B60, // pink
            0xFF8E24AA, // purple
            0xFF5E35B1, // deep purple
            0xFF3949AB, // indigo
            0xFF1E88E5, // blue
            0xFF039BE5, // light blue
            0xFF00ACC1, // cyan
            0xFF00897B, // teal
            0xFF43A047, // green
            0xFF7CB342, // light green
            0xFFC0CA33, // lime
            0xFFFDD835, // yellow
            0xFFFB8C00, // orange
            0xFFF4511E, // deep orange
            0xFF6D4C41, // brown
    };

    private SmbDeviceColor() {
    }

    /** Opaque ARGB; every device derives the same colour from the same id. */
    public static int of(@NonNull String clientId) {
        return PALETTE[indexOf(clientId)];
    }

    /** lowbias32 mixing, so the spread survives any id shape. */
    static int indexOf(@NonNull String clientId) {
        int h = clientId.hashCode();
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return (h & 0x7fffffff) % PALETTE.length;
    }
}
