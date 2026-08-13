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
 * How many things this app asks a share for at once.
 *
 * <p>Reading a share is bound by round trips, not by bandwidth: a gallery's {@code metadata.json}
 * is a couple of kilobytes, and reading twelve of them one after another took 612 ms against a real
 * NAS while the sort that followed took 1 ms. Asking for several at once is what makes that time
 * disappear, and the only question is how many.
 *
 * <p><b>There is no right answer to carry in the source.</b> The figure depends on the NAS, the
 * link, and what else is using both, so it is a setting with a measured default rather than a
 * constant. What the defaults were measured to be is recorded on each one below; the settings
 * screen can re-measure any particular share.
 *
 * <p>Two settings and not one, because the two workloads are not the same shape. Metadata is many
 * tiny files where the cost is almost entirely the round trip. Page images are megabytes each,
 * where enough concurrent readers eventually saturate the link and more of them only adds
 * contention. A single number would have to be wrong for one of them.
 */
public final class SmbConcurrency {

    /**
     * Reading small files: {@code metadata.json} for the inventory.
     *
     * <p>Six. Measured on a real NAS over WiFi, twelve galleries, median of four runs: serial
     * 612 ms, two 409 ms, four 245 ms, six 171 ms, eight 166 ms. Six and eight are the same answer
     * inside the noise, so the curve is flat by six and going higher only opens more sockets.
     */
    public static final int DEFAULT_METADATA = 6;

    /**
     * Reading large files: page images for the preview grid and the reader.
     *
     * <p>Six as well, but for a different reason and from a different place: it is the value the
     * preview prefetch has used since it was written, on the reasoning that a local share has
     * effectively unlimited bandwidth. That has never been measured the way the metadata figure
     * has, which is one of the things the settings screen's benchmark exists to check.
     */
    public static final int DEFAULT_IMAGE = 6;

    /**
     * One is meaningful — it means "serial", and it is the right answer for a share that misbehaves
     * under concurrency. The ceiling is there because past a point the threads are only queueing
     * against each other and a runaway value would be a way to make the app worse by hand.
     */
    public static final int MIN = 1;
    public static final int MAX = 16;

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
     * Resizes a pool to match the current setting, so a change takes effect without a restart.
     *
     * <p>The order matters and is not symmetric: growing means raising the maximum before the core,
     * shrinking means lowering the core before the maximum. Done the other way round,
     * {@link ThreadPoolExecutor} rejects the intermediate state where core exceeds maximum.
     *
     * <p>Cheap enough to call before every batch — it compares first and does nothing in the
     * ordinary case where the setting has not moved.
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
