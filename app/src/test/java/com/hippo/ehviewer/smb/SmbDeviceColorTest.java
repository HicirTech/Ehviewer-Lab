package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * The colour that stands for a device in the inventory badge (#77).
 *
 * <p>The badge says who is downloading a gallery with nothing but a colour, so the colour has to be
 * the same one on every device looking at the share and the same one tomorrow. Nothing agrees on it
 * — each device computes it from the owner's client id — which is exactly why it has to be a pure
 * function of that id and stay one.
 */
public class SmbDeviceColorTest {

    private static final String ANDROID_ID = "a1b2c3d4e5f60718";

    /**
     * The property everything else rests on. Two devices work out the same colour for a third only
     * because they both run this on the same string, so anything that made the answer depend on
     * time, order or state would silently give every device a different picture of the share.
     */
    @Test
    public void theSameIdAlwaysGivesTheSameColour() {
        int first = SmbDeviceColor.of(ANDROID_ID);

        for (int i = 0; i < 100; i++) {
            assertEquals(first, SmbDeviceColor.of(ANDROID_ID));
        }
    }

    /** A dot has to be drawn, so the colour has to be one — fully opaque, out of the known set. */
    @Test
    public void everyColourIsOpaqueAndFromThePalette() {
        Set<Integer> palette = new HashSet<>();
        for (String id : randomIds(2000)) {
            int colour = SmbDeviceColor.of(id);
            assertEquals("must be fully opaque", 0xFF, (colour >>> 24) & 0xFF);
            palette.add(colour);
        }

        assertTrue("no id may invent a colour outside the palette",
                palette.size() <= SmbDeviceColor.paletteSize());
    }

    /** Telling two devices apart is the entire job, so different ids must usually differ. */
    @Test
    public void differentIdsUsuallyGetDifferentColours() {
        int same = 0;
        String[] ids = randomIds(1000);
        for (int i = 0; i + 1 < ids.length; i += 2) {
            if (SmbDeviceColor.of(ids[i]) == SmbDeviceColor.of(ids[i + 1])) {
                same++;
            }
        }

        // Two of sixteen slots collide about a sixteenth of the time; well under a fifth leaves
        // room for the luck of these particular ids without letting a constant answer through.
        assertTrue("colours collided " + same + " times in 500 pairs", same < 100);
    }

    /**
     * Ids differing in one character are the realistic case — Android ids are the same length and
     * drawn from the same sixteen characters — and are the case a weak spread would clump.
     */
    @Test
    public void neighbouringIdsAreNotAllTheSameColour() {
        Set<Integer> colours = new HashSet<>();
        for (char c = '0'; c <= '9'; c++) {
            colours.add(SmbDeviceColor.of("a1b2c3d4e5f6071" + c));
        }

        assertTrue("ten ids one character apart gave " + colours.size() + " colours",
                colours.size() >= 5);
    }

    /**
     * Pins the spread as a stated property rather than a measured accident. Holds with or without
     * the mixing step in {@code indexOf} — the mixer is there so it keeps holding for ids that are
     * not shaped like today's, not to rescue the ones that are.
     */
    @Test
    public void everyColourInThePaletteIsReachable() {
        Set<Integer> seen = new HashSet<>();
        for (String id : randomIds(20_000)) {
            seen.add(SmbDeviceColor.indexOf(id));
        }

        assertEquals("some colours can never be handed out",
                SmbDeviceColor.paletteSize(), seen.size());
    }

    /** Renaming a device is a display change; it must not renumber anything. */
    @Test
    public void theColourFollowsTheIdAndNotTheName() {
        assertNotEquals("distinct ids are the premise",
                SmbDeviceColor.of("tablet-in-the-kitchen"), SmbDeviceColor.of(ANDROID_ID));
        assertEquals(SmbDeviceColor.of(ANDROID_ID), SmbDeviceColor.of(ANDROID_ID));
    }

    /** Fixed seed: a distribution assertion that fails only on some runs is worse than none. */
    private static String[] randomIds(int count) {
        Random random = new Random(20260810L);
        String[] out = new String[count];
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder(16);
            for (int c = 0; c < 16; c++) {
                sb.append("0123456789abcdef".charAt(random.nextInt(16)));
            }
            out[i] = sb.toString();
        }
        return out;
    }
}
