/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The sweep itself needs a share; the choice it makes from the sweep does not, and the choice is
 * where the subtle mistakes live. Plain JUnit over {@link SmbAutoTune#pickBest}.
 */
public class SmbAutoTuneTest {

    private static Map<Integer, Long> times(Object... kv) {
        Map<Integer, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((Integer) kv[i], ((Number) kv[i + 1]).longValue());
        }
        return m;
    }

    @Test
    public void theFastestLevelWins() {
        assertEquals(16, SmbAutoTune.pickBest(times(
                1, 6095, 2, 3780, 4, 2113, 6, 1402, 8, 1051, 16, 484)));
    }

    /**
     * The tie-break, and the reason it exists: run-to-run noise is bigger than a few percent, and
     * of two levels that close, the one holding fewer sockets open against the NAS should win.
     * Six at 166 ms against eight at 160 ms is the measured case that motivated it.
     */
    @Test
    public void aLowerLevelWithinTheMarginBeatsTheNominalWinner() {
        assertEquals(6, SmbAutoTune.pickBest(times(1, 612, 2, 409, 4, 245, 6, 166, 8, 160)));
    }

    /**
     * The margin is inclusive: a level exactly on the boundary counts. ceil(100 * 1.08) = 108,
     * and the level sitting at precisely 108 ms must still take the crown from the one at 100.
     * Pinned because an off-by-one here (&lt; for &le;) survived every other test.
     */
    @Test
    public void aLevelExactlyOnTheMarginStillCounts() {
        assertEquals(4, SmbAutoTune.pickBest(times(4, 108, 8, 100)));
    }

    /** But a genuinely faster higher level is not thrown away by the tie-break. */
    @Test
    public void theMarginDoesNotSwallowARealImprovement() {
        assertEquals(16, SmbAutoTune.pickBest(times(6, 1402, 8, 1051, 12, 720, 16, 484)));
    }

    /** Serial can win. A share that dislikes concurrency should get concurrency of one. */
    @Test
    public void serialWinsWhenSerialIsFastest() {
        assertEquals(1, SmbAutoTune.pickBest(times(1, 100, 2, 150, 4, 300, 8, 700)));
    }

    /** An empty sweep falls back to the default rather than crowning nothing. */
    @Test
    public void anEmptySweepYieldsTheDefault() {
        assertEquals(SmbConcurrency.DEFAULT_METADATA,
                SmbAutoTune.pickBest(new LinkedHashMap<>()));
    }

    /** Whatever wins must already be a value the clamp will accept back. */
    @Test
    public void theWinnerIsAlwaysWithinTheSettableRange() {
        assertEquals(64, SmbAutoTune.pickBest(times(48, 900, 64, 500)));
        // A corrupt map with an out-of-range key must not leak through.
        assertEquals(SmbConcurrency.DEFAULT_METADATA,
                SmbAutoTune.pickBest(times(999, 100L)));
    }
}
