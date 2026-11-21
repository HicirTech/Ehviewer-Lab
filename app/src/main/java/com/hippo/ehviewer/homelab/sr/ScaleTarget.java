/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;

/** What super-resolution aims for (#207): the device's screen, or one factor for every page. */
public enum ScaleTarget {
    DEVICE("device", ScaleRule.NONE),
    X2("2", 2),
    X3("3", 3),
    X4("4", 4);

    @NonNull
    public final String value;
    private final int mFixedScale;

    ScaleTarget(@NonNull String value, int fixedScale) {
        this.value = value;
        mFixedScale = fixedScale;
    }

    /** {@link ScaleRule#NONE} when the page stays as it is. */
    public int scaleFor(int deviceLongSide, int width, int height) {
        return this == DEVICE ? ScaleRule.pick(deviceLongSide, width, height) : mFixedScale;
    }

    @NonNull
    public CharSequence label(@NonNull Context context) {
        return this == DEVICE ? context.getString(R.string.settings_homelab_sr_target_device)
                : context.getString(R.string.settings_homelab_sr_target_scale, mFixedScale);
    }

    /** {@link #DEVICE} for a value it does not know. */
    @NonNull
    public static ScaleTarget fromValue(@Nullable String value) {
        for (ScaleTarget target : values()) {
            if (target.value.equals(value)) {
                return target;
            }
        }
        return DEVICE;
    }
}
