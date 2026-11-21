/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

/** How much to upscale a page: its short side should reach the device's long side (#173). */
public final class ScaleRule {

    /** The scales realesr-animevideov3 offers, smallest first. */
    static final int[] SCALES = {2, 3, 4};
    public static final int NONE = 0;

    private ScaleRule() {}

    /** {@link #NONE} when the page is already large enough, else the smallest scale that is (4 at most). */
    public static int pick(int deviceLongSide, int width, int height) {
        int shortSide = Math.min(width, height);
        if (shortSide <= 0 || shortSide >= deviceLongSide) {
            return NONE;
        }
        for (int scale : SCALES) {
            if ((long) shortSide * scale >= deviceLongSide) {
                return scale;
            }
        }
        return SCALES[SCALES.length - 1];
    }
}
