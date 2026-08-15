/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertFalse;
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

/** Shims die with their pipe; anything in the shim dir at process start is a leak to sweep. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbShimsTest {

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("smb-shims").toFile();
        plant("sDir", dir);
        plant("sSwept", false);
    }

    @After
    public void tearDown() throws Exception {
        plant("sDir", null);
        plant("sSwept", false);
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
        Field f = SmbShims.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private void touch(String name) throws Exception {
        try (FileOutputStream os = new FileOutputStream(new File(dir, name))) {
            os.write(1);
        }
    }

    /** A killed process leaves its shims behind; the next process's first use removes them. */
    @Test
    public void firstUseSweepsTheLeftovers() throws Exception {
        touch("smb_img_123.tmp");
        touch("smb_preview_456.tmp");

        SmbShims.dir();

        assertFalse(new File(dir, "smb_img_123.tmp").exists());
        assertFalse(new File(dir, "smb_preview_456.tmp").exists());
    }

    /** Once per process only — a live shim created after the sweep must survive later calls. */
    @Test
    public void theSweepDoesNotEatLiveShims() throws Exception {
        SmbShims.dir();
        touch("smb_img_live.tmp");

        SmbShims.dir();

        assertTrue("a live shim was swept", new File(dir, "smb_img_live.tmp").exists());
    }
}
