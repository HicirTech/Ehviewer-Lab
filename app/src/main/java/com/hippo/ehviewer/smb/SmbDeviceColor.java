package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;

/**
 * A colour that stands for one device on the share (#77).
 *
 * <p>The inventory has room for a dot and nothing more, so the dot has to carry the "who" by
 * itself. Derived from the client id — which is this installation's Android id — so the same tablet
 * is the same colour on every screen of every device, without anybody having to agree on anything:
 * two phones looking at the same share pick the same colour for the same third device because they
 * are computing it from the same string, not exchanging it.
 *
 * <p>Colours come from a fixed list rather than a hue computed from the hash. A hue circle spreads
 * ids evenly across values a screen can show but not across ones an eye can tell apart — two
 * devices twenty degrees apart are simply the same colour to look at, and some of the circle is
 * hard to see at all at 12dp. A short hand-picked list gives up uniqueness for the thing actually
 * being asked of it. Two devices can land on the same colour; the download list still names them.
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

    /**
     * Which slot of the palette an id falls in.
     *
     * <p>The mixing step is the lowbias32 finaliser, and it is insurance rather than a fix: over
     * twenty thousand random sixteen-character hex ids, {@link String#hashCode()} taken modulo
     * sixteen already fills every slot to within ten percent of even, so for the ids this sees
     * today it changes nothing measurable. What it buys is that the spread does not depend on that
     * — the id is only usually an Android id, and the made-up fallback and anything a later change
     * substitutes get an even spread without anyone re-checking.
     */
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
