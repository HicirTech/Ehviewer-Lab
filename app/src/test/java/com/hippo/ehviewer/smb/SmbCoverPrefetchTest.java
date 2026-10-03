/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.streampipe.InputStreamPipe;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;

/** The cover prefetch: bytes live in memory, and the one disk file involved dies with its pipe. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbCoverPrefetchTest {

    private File shimDir;
    private File legacyDir;

    @Before
    public void setUp() throws Exception {
        shimDir = Files.createTempDirectory("smb-cover-shim").toFile();
        legacyDir = Files.createTempDirectory("smb-cover-legacy").toFile();
        plantShim(shimDir);
        plant("sLegacyDir", legacyDir);
        setStatic("sLegacySwept", false);
        clearBuffer();
    }

    @After
    public void tearDown() throws Exception {
        plantShim(null);
        plant("sLegacyDir", null);
        setStatic("sLegacySwept", false);
        clearBuffer();
        for (File d : new File[]{shimDir, legacyDir}) {
            File[] files = d.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            d.delete();
        }
    }

    private static void plantShim(java.io.File value) throws Exception {
        Field f = SmbShims.class.getDeclaredField("sDir");
        f.setAccessible(true);
        f.set(null, value);
        Field s = SmbShims.class.getDeclaredField("sSwept");
        s.setAccessible(true);
        // Swept: these tests are about pipes, not the startup sweep.
        s.set(null, true);
    }

    private static void plant(String name, Object value) throws Exception {
        Field f = SmbCoverPrefetch.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static void setStatic(String name, Object value) throws Exception {
        plant(name, value);
    }

    private static void clearBuffer() throws Exception {
        Field f = SmbCoverPrefetch.class.getDeclaredField("BUFFER");
        f.setAccessible(true);
        ((java.util.Map<?, ?>) f.get(null)).clear();
        Field n = SmbCoverPrefetch.class.getDeclaredField("sBufferedBytes");
        n.setAccessible(true);
        n.set(null, 0);
        Field r = SmbCoverPrefetch.class.getDeclaredField("REQUESTED");
        r.setAccessible(true);
        ((java.util.Set<?>) r.get(null)).clear();
    }

    private static void put(long gid, byte[] bytes) throws Exception {
        Method m = SmbCoverPrefetch.class.getDeclaredMethod("put", long.class, byte[].class);
        m.setAccessible(true);
        m.invoke(null, gid, bytes);
    }

    private static byte[] drain(InputStreamPipe pipe) throws Exception {
        pipe.obtain();
        try {
            InputStream is = pipe.open();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int b;
            while ((b = is.read()) != -1) {
                out.write(b);
            }
            return out.toByteArray();
        } finally {
            pipe.close();
            pipe.release();
        }
    }

    @Test
    public void theDecodeShimDoesNotOutliveTheDecode() throws Exception {
        put(1L, new byte[]{1, 2, 3});
        InputStreamPipe pipe = SmbCoverPrefetch.pipeFor(1L);
        assertNotNull(pipe);

        byte[] got = drain(pipe);

        assertArrayEquals(new byte[]{1, 2, 3}, got);
        assertEquals("a decode shim survived its decode", 0,
                shimDir.listFiles() == null ? 0 : shimDir.listFiles().length);
    }

    @Test
    public void anUnbufferedGidYieldsNoPipe() {
        assertNull(SmbCoverPrefetch.pipeFor(999L));
    }

    @Test
    public void evictForgetsExactlyItsOwnGallery() throws Exception {
        put(100L, new byte[]{1});
        put(1001L, new byte[]{2});

        SmbCoverPrefetch.evict(100L);

        assertNull(SmbCoverPrefetch.pipeFor(100L));
        assertNotNull("a neighbouring gid went with the eviction",
                SmbCoverPrefetch.pipeFor(1001L));
    }

    @Test
    public void theBufferDropsTheColdestPastItsCap() throws Exception {
        byte[] threeMb = new byte[3 * 1024 * 1024];
        put(1L, threeMb);
        put(2L, threeMb);
        assertNotNull(SmbCoverPrefetch.pipeFor(1L));   // touch 1 so 2 is the coldest

        put(3L, threeMb);                              // 9 MB total, cap is 8

        assertNull("the coldest entry should have been dropped",
                SmbCoverPrefetch.pipeFor(2L));
        assertNotNull(SmbCoverPrefetch.pipeFor(1L));
        assertNotNull(SmbCoverPrefetch.pipeFor(3L));
    }

    @Test
    public void theLegacyNamedFileCacheIsSweptAway() throws Exception {
        File stale = new File(legacyDir, "123");
        try (FileOutputStream os = new FileOutputStream(stale)) {
            os.write("cover bytes from hl.8".getBytes());
        }

        // Not through prefetch(): it consults Settings, which this Robolectric config never sets up.
        Method m = SmbCoverPrefetch.class.getDeclaredMethod("sweepLegacyOnce");
        m.setAccessible(true);
        m.invoke(null);

        assertTrue("the hl.8 cover cache should be gone, directory and all",
                !stale.exists() && !legacyDir.exists());
    }
}
