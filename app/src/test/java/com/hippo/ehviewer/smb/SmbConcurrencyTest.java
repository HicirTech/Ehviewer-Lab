/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** The two things SmbConcurrency has to get right, neither of which is obvious from reading it: a stored value that cannot be trusted, and a pool resize */
public class SmbConcurrencyTest {

    private static ThreadPoolExecutor pool(int size) {
        return new ThreadPoolExecutor(size, size, 10L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
    }

    // --- clamp ----------------------------------------------------------------------------
    //
    // The value comes out of a preference a user edits. Zero would mean a pool that runs nothing,
    // and the share read would simply never return — a hang is a worse answer than a default.

    @Test
    public void clamp_keepsAnythingInRange() {
        for (int i = SmbConcurrency.MIN; i <= SmbConcurrency.MAX; i++) {
            assertEquals(i, SmbConcurrency.clamp(i, 6));
        }
    }

    @Test
    public void clamp_refusesZeroAndNegative() {
        assertEquals(6, SmbConcurrency.clamp(0, 6));
        assertEquals(6, SmbConcurrency.clamp(-1, 6));
    }

    @Test
    public void clamp_refusesAbsurdlyLarge() {
        assertEquals(6, SmbConcurrency.clamp(SmbConcurrency.MAX + 1, 6));
        assertEquals(6, SmbConcurrency.clamp(10_000, 6));
    }

    /** One is a real answer — "do not overlap anything" — and must survive. */
    @Test
    public void clamp_keepsOne() {
        assertEquals(1, SmbConcurrency.clamp(1, 6));
    }

    // --- resize ---------------------------------------------------------------------------
    //
    // ThreadPoolExecutor throws if core is ever set above maximum, so growing and shrinking need
    // the two calls in opposite orders. Doing it one way for both is the bug these guard.

    @Test
    public void resize_growsWithoutThrowing() {
        ThreadPoolExecutor p = pool(2);

        SmbConcurrency.resize(p, 8);

        assertEquals(8, p.getCorePoolSize());
        assertEquals(8, p.getMaximumPoolSize());
    }

    @Test
    public void resize_shrinksWithoutThrowing() {
        ThreadPoolExecutor p = pool(8);

        SmbConcurrency.resize(p, 2);

        assertEquals(2, p.getCorePoolSize());
        assertEquals(2, p.getMaximumPoolSize());
    }

    /** Back and forth, because a resize that only works once is a resize that works by accident. */
    @Test
    public void resize_survivesRepeatedChangesInBothDirections() {
        ThreadPoolExecutor p = pool(6);

        for (int size : new int[]{1, 16, 4, 12, 2, 6}) {
            SmbConcurrency.resize(p, size);
            assertEquals("core after resizing to " + size, size, p.getCorePoolSize());
            assertEquals("max after resizing to " + size, size, p.getMaximumPoolSize());
        }
    }

    @Test
    public void resize_isANoOpWhenTheSizeAlreadyMatches() {
        ThreadPoolExecutor p = pool(6);

        SmbConcurrency.resize(p, 6);

        assertEquals(6, p.getCorePoolSize());
        assertEquals(6, p.getMaximumPoolSize());
    }
}
