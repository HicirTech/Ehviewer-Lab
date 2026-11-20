/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The page's short side should reach the device's long side, with the smallest scale that does it (#173). */
public class ScaleRuleTest {

    private static final int LONG_SIDE = 2960;

    @Test
    public void picksTheSmallestScaleThatReachesTheLongSide() {
        int[][] cases = {
                // {width, height, expected scale}
                {2960, 4186, ScaleRule.NONE},
                {3000, 4000, ScaleRule.NONE},
                {2959, 4000, 2},
                {1480, 2093, 2},
                {1479, 2091, 3},
                {987, 1396, 3},
                {986, 1394, 4},
                {740, 1046, 4},
                {739, 1045, 4},
                {200, 300, 4},
                {1810, 1280, 3},
                {0, 1000, ScaleRule.NONE},
        };
        for (int[] c : cases) {
            assertEquals(c[0] + "x" + c[1], c[2], ScaleRule.pick(LONG_SIDE, c[0], c[1]));
        }
    }
}
