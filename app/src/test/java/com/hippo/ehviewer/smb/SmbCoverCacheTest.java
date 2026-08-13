/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;

/**
 * The property that makes the cover staging an acceptable thing to have at all: <b>nothing
 * survives a restart</b>. The share is the only source of truth in a multi-device app, and a
 * staged cover trusted across processes is a device quietly preferring its own past over what
 * another device may have changed — the first version of {@code SmbCoverCache} did exactly that,
 * and these tests exist so it cannot come back.
 *
 * <p>{@code sCacheDir} is planted by reflection, the same way {@code SmbPreviewCacheEvictTest}
 * does it, because {@code EhApplication.getInstance()} does not exist under Robolectric.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbCoverCacheTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("smb-cover-cache").toFile();
        plant("sCacheDir", dir);
        setSwept(false);
    }

    @After
    public void tearDown() throws Exception {
        plant("sCacheDir", null);
        setSwept(false);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    private static void plant(String name, Object value) throws Exception {
        Field field = SmbCoverCache.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void setSwept(boolean value) throws Exception {
        Field field = SmbCoverCache.class.getDeclaredField("sSweptThisProcess");
        field.setAccessible(true);
        field.set(null, value);
    }

    private File plantLeftover(long gid) throws Exception {
        File f = new File(dir, Long.toString(gid));
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write("stale cover from a previous process".getBytes());
        }
        return f;
    }

    // --- the restart boundary ----------------------------------------------------------------

    /**
     * The core claim. A file left by a previous process is a dead process's view of a share that
     * other devices write to; the first touch of the directory must throw it away, not serve it.
     */
    @Test
    public void aLeftoverFromAPreviousProcessIsNeverServed() throws Exception {
        File leftover = plantLeftover(123L);

        assertNull("a pre-restart file came back as a staged cover",
                SmbCoverCache.staged(123L));
        assertFalse("the leftover should have been deleted, not merely ignored",
                leftover.exists());
    }

    /** The sweep is the first touch, whatever that touch is — eviction included. */
    @Test
    public void theSweepRunsWhicheverEntryPointComesFirst() throws Exception {
        File leftover = plantLeftover(7L);

        SmbCoverCache.evict(999L);      // touches the directory for an unrelated gid

        assertFalse("a leftover survived because the first touch was not staged()",
                leftover.exists());
    }

    /** One wipe per process, not one per call: what this process stages must stay. */
    @Test
    public void whatThisProcessStagesSurvivesLaterCalls() throws Exception {
        assertNull(SmbCoverCache.staged(1L));           // first touch, sweep happens
        File mine = plantLeftover(42L);                 // stands in for a fetch by this process

        assertNotNull("a file written after the sweep must be served",
                SmbCoverCache.staged(42L));
        assertEquals(mine, SmbCoverCache.staged(42L));
    }

    // --- staged() ----------------------------------------------------------------------------

    /** Zero bytes is a failed fetch or an absent cover; handing it to a decoder helps nobody. */
    @Test
    public void anEmptyFileIsNotAStagedCover() throws Exception {
        SmbCoverCache.staged(1L);                       // sweep first
        assertTrue(new File(dir, "5").createNewFile()); // zero-length

        assertNull(SmbCoverCache.staged(5L));
    }

    // --- evict() -----------------------------------------------------------------------------

    @Test
    public void evictRemovesExactlyItsOwnGallery() throws Exception {
        SmbCoverCache.staged(1L);                       // sweep first
        plantLeftover(100L);
        plantLeftover(1001L);

        SmbCoverCache.evict(100L);

        assertNull("evicted gallery still staged", SmbCoverCache.staged(100L));
        assertNotNull("a neighbouring gid was taken out by the eviction",
                SmbCoverCache.staged(1001L));
    }
}
