/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.Settings;

import java.util.concurrent.ThreadPoolExecutor;

public final class SmbConcurrency {

    /** Conservative on purpose: auto-tune measures the real share. */
    public static final int DEFAULT_METADATA = 6;

    public static final int DEFAULT_IMAGE = 6;

    // Workers are not sockets: jcifs-ng multiplexes them over SMB2 credits.
    public static final int MIN = 1;
    public static final int MAX = 128;

    private SmbConcurrency() {}

    public static int metadata() {
        return clamp(Settings.getSmbMetadataConcurrency(), DEFAULT_METADATA);
    }

    public static int image() {
        return clamp(Settings.getSmbImageConcurrency(), DEFAULT_IMAGE);
    }

    /** Out-of-range values give {@code fallback}, not the nearest bound. */
    public static int clamp(int value, int fallback) {
        if (value < MIN || value > MAX) {
            return fallback;
        }
        return value;
    }

    /** Grow max first, shrink core first: ThreadPoolExecutor rejects core > max. */
    public static void resize(@NonNull ThreadPoolExecutor pool, int size) {
        if (pool.getCorePoolSize() == size && pool.getMaximumPoolSize() == size) {
            return;
        }
        if (size > pool.getCorePoolSize()) {
            pool.setMaximumPoolSize(size);
            pool.setCorePoolSize(size);
        } else {
            pool.setCorePoolSize(size);
            pool.setMaximumPoolSize(size);
        }
    }
}
