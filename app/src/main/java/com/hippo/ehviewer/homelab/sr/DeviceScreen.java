/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.Display;

import androidx.annotation.NonNull;

/** The long side of the built-in screen in physical pixels, read once per process. */
final class DeviceScreen {

    private static int sLongSide;

    private DeviceScreen() {}

    static synchronized int longSide(@NonNull Context context) {
        if (sLongSide == 0) {
            Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            Display.Mode mode = display.getMode();
            sLongSide = Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight());
        }
        return sLongSide;
    }
}
