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

/** The #129 shape: preview bytes live in a bounded memory buffer, and the one disk file involved is an anonymous shim that dies with its pipe. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbPreviewCacheTest {

    private File shimDir;
    private File legacyDir;

    @Before
    public void setUp() throws Exception {
        shimDir = Files.createTempDirectory("smb-preview-shim").toFile();
        legacyDir = Files.createTempDirectory("smb-preview-legacy").toFile();
        plant("sShimDir", shimDir);
        plant("sLegacyDir", legacyDir);
        plant("sLegacySwept", false);
        clearBuffer();
    }

    @After
    public void tearDown() throws Exception {
        plant("sShimDir", null);
        plant("sLegacyDir", null);
        plant("sLegacySwept", false);
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

    private static void plant(String name, Object value) throws Exception {
        Field f = SmbPreviewCache.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static void clearBuffer() throws Exception {
        Field f = SmbPreviewCache.class.getDeclaredField("BUFFER");
        f.setAccessible(true);
        ((java.util.Map<?, ?>) f.get(null)).clear();
        Field n = SmbPreviewCache.class.getDeclaredField("sBufferedBytes");
        n.setAccessible(true);
        n.set(null, 0);
        Field r = SmbPreviewCache.class.getDeclaredField("PREFETCHED_GIDS");
        r.setAccessible(true);
        ((java.util.Set<?>) r.get(null)).clear();
    }

    private static void put(long gid, int index, byte[] bytes) throws Exception {
        Method m = SmbPreviewCache.class.getDeclaredMethod("put", String.class, byte[].class);
        m.setAccessible(true);
        m.invoke(null, gid + ":" + index, bytes);
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

    // --- the disk boundary -------------------------------------------------------------------

    /** Serving a preview writes one shim, the decode reads it, and after close the disk is clean. */
    @Test
    public void theDecodeShimDoesNotOutliveTheDecode() throws Exception {
        put(1L, 0, new byte[]{1, 2, 3});
        InputStreamPipe pipe = SmbPreviewCache.pipeFor(1L, 0);
        assertNotNull(pipe);

        byte[] got = drain(pipe);

        assertArrayEquals(new byte[]{1, 2, 3}, got);
        assertEquals("a decode shim survived its decode", 0,
                shimDir.listFiles() == null ? 0 : shimDir.listFiles().length);
    }

    /** The bytes come from memory; an unbuffered page means "go ask the share", not an error. */
    @Test
    public void anUnbufferedPageYieldsNoPipe() {
        assertNull(SmbPreviewCache.pipeFor(999L, 0));
    }

    /** Pages are individual entries — index 0 buffered says nothing about index 1. */
    @Test
    public void pagesAreBufferedPerIndex() throws Exception {
        put(1L, 0, new byte[]{1});

        assertNotNull(SmbPreviewCache.pipeFor(1L, 0));
        assertNull(SmbPreviewCache.pipeFor(1L, 1));
    }

    // --- eviction ----------------------------------------------------------------------------

    @Test
    public void evictForgetsExactlyItsOwnGallery() throws Exception {
        put(100L, 0, new byte[]{1});
        put(100L, 12, new byte[]{2});
        put(1001L, 0, new byte[]{3});
        put(10L, 0, new byte[]{4});

        SmbPreviewCache.evictGallery(100L);

        assertNull(SmbPreviewCache.pipeFor(100L, 0));
        assertNull("a two-digit page index survived", SmbPreviewCache.pipeFor(100L, 12));
        assertNotNull("a gid sharing a prefix went with the eviction",
                SmbPreviewCache.pipeFor(1001L, 0));
        assertNotNull("a gid the target starts with went with the eviction",
                SmbPreviewCache.pipeFor(10L, 0));
    }

    /** The buffer is bounded; past the cap the least recently touched pages fall out. */
    @Test
    public void theBufferDropsTheColdestPastItsCap() throws Exception {
        byte[] sixMb = new byte[6 * 1024 * 1024];
        put(1L, 0, sixMb);
        put(1L, 1, sixMb);
        assertNotNull(SmbPreviewCache.pipeFor(1L, 0));   // touch page 0 so page 1 is the coldest

        put(1L, 2, sixMb);                               // 18 MB total, cap is 16

        assertNull("the coldest page should have been dropped",
                SmbPreviewCache.pipeFor(1L, 1));
        assertNotNull(SmbPreviewCache.pipeFor(1L, 0));
        assertNotNull(SmbPreviewCache.pipeFor(1L, 2));
    }

    // --- the named-file leftovers ------------------------------------------------------------

    /** Earlier builds shipped previews as named files under cache/smb_preview. */
    @Test
    public void theLegacyNamedFileCacheIsSweptAway() throws Exception {
        File stale = new File(legacyDir, "123-0");
        try (FileOutputStream os = new FileOutputStream(stale)) {
            os.write("preview bytes from the old build".getBytes());
        }

        // Invoked directly rather than through prefetchGallery(): the public entry point
        // consults Settings, and this test is about the sweep, not configuration gating.
        Method m = SmbPreviewCache.class.getDeclaredMethod("sweepLegacyOnce");
        m.setAccessible(true);
        m.invoke(null);

        assertTrue("the old preview cache should be gone, directory and all",
                !stale.exists() && !legacyDir.exists());
    }
}
