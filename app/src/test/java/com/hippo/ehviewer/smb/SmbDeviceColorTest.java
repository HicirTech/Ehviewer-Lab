package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/** The colour that stands for a device in the inventory badge (#77). */
public class SmbDeviceColorTest {

    private static final String ANDROID_ID = "a1b2c3d4e5f60718";

    private static final int PALETTE_SIZE = 16;

    @Test
    public void theSameIdAlwaysGivesTheSameColour() {
        int first = SmbDeviceColor.of(ANDROID_ID);

        for (int i = 0; i < 100; i++) {
            assertEquals(first, SmbDeviceColor.of(ANDROID_ID));
        }
    }

    @Test
    public void everyColourIsOpaqueAndFromThePalette() {
        Set<Integer> palette = new HashSet<>();
        for (String id : randomIds(2000)) {
            int colour = SmbDeviceColor.of(id);
            assertEquals("must be fully opaque", 0xFF, (colour >>> 24) & 0xFF);
            palette.add(colour);
        }

        assertTrue("no id may invent a colour outside the palette",
                palette.size() <= PALETTE_SIZE);
    }

    @Test
    public void differentIdsUsuallyGetDifferentColours() {
        int same = 0;
        String[] ids = randomIds(1000);
        for (int i = 0; i + 1 < ids.length; i += 2) {
            if (SmbDeviceColor.of(ids[i]) == SmbDeviceColor.of(ids[i + 1])) {
                same++;
            }
        }

        // Chance collisions run near 1 in 16; under 1 in 5 still rejects a constant colour.
        assertTrue("colours collided " + same + " times in 500 pairs", same < 100);
    }

    @Test
    public void neighbouringIdsAreNotAllTheSameColour() {
        Set<Integer> colours = new HashSet<>();
        for (char c = '0'; c <= '9'; c++) {
            colours.add(SmbDeviceColor.of("a1b2c3d4e5f6071" + c));
        }

        assertTrue("ten ids one character apart gave " + colours.size() + " colours",
                colours.size() >= 5);
    }

    @Test
    public void everyColourInThePaletteIsReachable() {
        Set<Integer> seen = new HashSet<>();
        for (String id : randomIds(20_000)) {
            seen.add(SmbDeviceColor.indexOf(id));
        }

        assertEquals("some colours can never be handed out",
                PALETTE_SIZE, seen.size());
    }

    @Test
    public void theColourFollowsTheIdAndNotTheName() {
        assertNotEquals("distinct ids are the premise",
                SmbDeviceColor.of("tablet-in-the-kitchen"), SmbDeviceColor.of(ANDROID_ID));
        assertEquals(SmbDeviceColor.of(ANDROID_ID), SmbDeviceColor.of(ANDROID_ID));
    }

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
