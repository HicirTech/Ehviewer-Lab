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

/** How {@link SmbAutoTune#pickBest} picks a level from a sweep's timings. */
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

    @Test
    public void aLowerLevelWithinTheMarginBeatsTheNominalWinner() {
        assertEquals(6, SmbAutoTune.pickBest(times(1, 612, 2, 409, 4, 245, 6, 166, 8, 160)));
    }

    @Test
    public void aLevelExactlyOnTheMarginStillCounts() {
        assertEquals(4, SmbAutoTune.pickBest(times(4, 108, 8, 100)));
    }

    @Test
    public void theMarginDoesNotSwallowARealImprovement() {
        assertEquals(16, SmbAutoTune.pickBest(times(6, 1402, 8, 1051, 12, 720, 16, 484)));
    }

    @Test
    public void serialWinsWhenSerialIsFastest() {
        assertEquals(1, SmbAutoTune.pickBest(times(1, 100, 2, 150, 4, 300, 8, 700)));
    }

    @Test
    public void anEmptySweepYieldsTheDefault() {
        assertEquals(SmbConcurrency.DEFAULT_METADATA,
                SmbAutoTune.pickBest(new LinkedHashMap<>()));
    }

    @Test
    public void theWinnerIsAlwaysWithinTheSettableRange() {
        assertEquals(64, SmbAutoTune.pickBest(times(48, 900, 64, 500)));
        assertEquals(SmbConcurrency.DEFAULT_METADATA,
                SmbAutoTune.pickBest(times(999, 100L)));
    }
}
