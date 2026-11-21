/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The device target follows the screen; a fixed target upscales every page by its factor (#207). */
public class ScaleTargetTest {

    private static final int LONG_SIDE = 2960;

    @Test
    public void aFixedTargetUpscalesEveryPageByItsFactor() {
        assertEquals(2, ScaleTarget.X2.scaleFor(LONG_SIDE, 3000, 4000));
        assertEquals(3, ScaleTarget.X3.scaleFor(LONG_SIDE, 1280, 1810));
        assertEquals(4, ScaleTarget.X4.scaleFor(LONG_SIDE, 200, 300));
    }

    @Test
    public void theDeviceTargetFollowsTheScreen() {
        assertEquals(ScaleRule.NONE, ScaleTarget.DEVICE.scaleFor(LONG_SIDE, 3000, 4000));
        assertEquals(3, ScaleTarget.DEVICE.scaleFor(LONG_SIDE, 1280, 1810));
    }

    @Test
    public void storedValuesMapBackAndUnknownOnesMeanTheDevice() {
        for (ScaleTarget target : ScaleTarget.values()) {
            assertEquals(target, ScaleTarget.fromValue(target.value));
        }
        assertEquals(ScaleTarget.DEVICE, ScaleTarget.fromValue("5"));
        assertEquals(ScaleTarget.DEVICE, ScaleTarget.fromValue(null));
    }
}
