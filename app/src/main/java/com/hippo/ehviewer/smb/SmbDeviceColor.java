package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;

/**
 * A colour per device, derived from the client id so every device computes the same one (#77).
 * Hand-picked palette, not a hue circle — eyes can't tell 20° apart at 12dp; collisions are fine.
 */
public final class SmbDeviceColor {

    /**
     * Sixteen colours that stay legible at the size of the badge, against cover art of any colour,
     * and in both themes. Deliberately no near-neighbours: every pair is meant to be separable at a
     * glance rather than on inspection.
     */
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

    /** The colour for a device, as an opaque ARGB int. Same id in, same colour out, always. */
    public static int of(@NonNull String clientId) {
        return PALETTE[indexOf(clientId)];
    }

    /** Palette slot via lowbias32 mixing — insurance so the spread survives any id shape. */
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
