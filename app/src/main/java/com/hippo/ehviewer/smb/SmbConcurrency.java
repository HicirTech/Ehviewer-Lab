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

/**
 * Concurrency settings for share reads — round-trip-bound work, so parallelism is the whole
 * game and the right number is a property of the NAS/link, not the source. Two settings because
 * tiny-metadata and megabyte-image workloads have different shapes.
 */
public final class SmbConcurrency {

    /**
     * Conservative default, not an optimum — 140 galleries kept scaling past 16 (6.1s serial →
     * 0.48s at 16); auto-tune measures the real share and this only matters until it runs.
     */
    public static final int DEFAULT_METADATA = 6;

    /** Historical prefetch value, never measured like the metadata one — auto-tune checks it. */
    public static final int DEFAULT_IMAGE = 6;

    /**
     * 1 = serial (meaningful). The ceiling was raised twice (16→64→128) because auto-tune winners
     * kept landing on the lid — a winner on the lid is censored, not optimal. Workers are not
     * sockets: jcifs-ng multiplexes over SMB2 credits.
     */
    public static final int MIN = 1;
    public static final int MAX = 128;

    private SmbConcurrency() {}

    public static int metadata() {
        return clamp(Settings.getSmbMetadataConcurrency(), DEFAULT_METADATA);
    }

    public static int image() {
        return clamp(Settings.getSmbImageConcurrency(), DEFAULT_IMAGE);
    }

    /**
     * Keeps a stored value usable. Settings holds these as strings a user can edit, and a share
     * that reads zero files at a time would simply hang; falling back beats refusing to work.
     */
    public static int clamp(int value, int fallback) {
        if (value < MIN || value > MAX) {
            return fallback;
        }
        return value;
    }

    /**
     * Resizes without restart. Order is asymmetric on purpose: grow max-then-core, shrink
     * core-then-max, or ThreadPoolExecutor rejects the intermediate state.
     */
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
