/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.storage.DownloadState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The beat writer (#144): the schedule follows shouldBeat, only a landed publish counts as
 * being in touch, and silence past the staleness window triggers the re-read. The fixed delay
 * itself is 20s, so the beat body is driven directly (reflection, not a visibility change).
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbHeartbeatTest {

    /** A shell whose answers the test scripts. */
    private static final class ScriptedShell implements SmbHeartbeat.Shell {
        volatile boolean beatWanted;
        volatile boolean publishLands = true;
        final AtomicInteger publishes = new AtomicInteger();
        final AtomicInteger backFromSilence = new AtomicInteger();

        @Override
        public boolean publishSelf() {
            publishes.incrementAndGet();
            return publishLands;
        }

        @Override
        public boolean shouldBeat() {
            return beatWanted;
        }

        @Override
        public void onBackFromSilence() {
            backFromSilence.incrementAndGet();
        }
    }

    private static Object beatingOf(SmbHeartbeat hb) throws Exception {
        Field f = SmbHeartbeat.class.getDeclaredField("beating");
        f.setAccessible(true);
        return f.get(hb);
    }

    private static void setLastPublished(SmbHeartbeat hb, long value) throws Exception {
        Field f = SmbHeartbeat.class.getDeclaredField("lastPublishedAtMillis");
        f.setAccessible(true);
        f.set(hb, value);
    }

    private static void beat(SmbHeartbeat hb) throws Exception {
        Method m = SmbHeartbeat.class.getDeclaredMethod("beat");
        m.setAccessible(true);
        m.invoke(hb);
    }

    @Test
    public void theScheduleFollowsShouldBeat() throws Exception {
        ScriptedShell shell = new ScriptedShell();
        SmbHeartbeat hb = new SmbHeartbeat(shell);

        hb.sync();
        assertNull("nothing to say, no schedule", beatingOf(hb));

        shell.beatWanted = true;
        hb.sync();
        assertNotNull("work exists, the beat must run", beatingOf(hb));

        shell.beatWanted = false;
        hb.sync();
        assertNull("work done, the beat must stop", beatingOf(hb));
    }

    /** A publish that failed to land must not count as having been in touch with the share. */
    @Test
    public void onlyALandedPublishCounts() throws Exception {
        ScriptedShell shell = new ScriptedShell();
        SmbHeartbeat hb = new SmbHeartbeat(shell);
        shell.publishLands = false;

        CountDownLatch ran = new CountDownLatch(1);
        hb.publish();
        hb.execute(ran::countDown);
        assertTrue(ran.await(5, TimeUnit.SECONDS));

        Field f = SmbHeartbeat.class.getDeclaredField("lastPublishedAtMillis");
        f.setAccessible(true);
        assertEquals(0L, f.getLong(hb));
        assertEquals(1, shell.publishes.get());
    }

    /** Silent past the staleness window: others may have adopted the queue — go and look. */
    @Test
    public void returningFromSilenceRereadsTheQueue() throws Exception {
        ScriptedShell shell = new ScriptedShell();
        SmbHeartbeat hb = new SmbHeartbeat(shell);
        setLastPublished(hb, System.currentTimeMillis() - DownloadState.STALE_AFTER_MS - 1_000L);

        beat(hb);

        assertEquals(1, shell.backFromSilence.get());
    }

    @Test
    public void aShortGapIsNotSilence() throws Exception {
        ScriptedShell shell = new ScriptedShell();
        SmbHeartbeat hb = new SmbHeartbeat(shell);
        setLastPublished(hb, System.currentTimeMillis() - 10_000L);

        beat(hb);

        assertEquals(0, shell.backFromSilence.get());
    }

    /** Before the first successful publish there is no silence to return from. */
    @Test
    public void theFirstBeatIsNeverAReturn() throws Exception {
        ScriptedShell shell = new ScriptedShell();
        SmbHeartbeat hb = new SmbHeartbeat(shell);

        beat(hb);

        assertEquals(0, shell.backFromSilence.get());
        assertFalse(shell.publishes.get() == 0);
    }
}
