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

/** Deleting a gallery from the share has to take its cached previews with it, and take nothing else. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbPreviewCacheEvictTest {

    private static final long TARGET_GID = 100L;

    private File dir;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("smb-preview-cache").toFile();
        plantCacheDir(dir);
    }

    @After
    public void tearDown() throws Exception {
        plantCacheDir(null);
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

    private static void plantCacheDir(File value) throws Exception {
        Field field = SmbPreviewCache.class.getDeclaredField("sCacheDir");
        field.setAccessible(true);
        field.set(null, value);
    }

    private void touch(String name) throws Exception {
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(new byte[]{1});
        }
        assertTrue("fixture " + name + " was not created", f.isFile());
    }

    private boolean exists(String name) {
        return new File(dir, name).isFile();
    }

    @Test
    public void removesEveryPageOfTheDeletedGallery() throws Exception {
        touch("100-0");
        touch("100-1");
        touch("100-12");

        SmbPreviewCache.evictGallery(TARGET_GID);

        assertFalse("page 0 survived", exists("100-0"));
        assertFalse("page 1 survived", exists("100-1"));
        assertFalse("a two-digit page index survived", exists("100-12"));
    }

    /** A gid that merely starts with the deleted one shares no data with it. */
    @Test
    public void keepsGalleriesWhoseGidSharesAPrefix() throws Exception {
        touch("100-0");
        touch("1000-0");
        touch("1001-5");

        SmbPreviewCache.evictGallery(TARGET_GID);

        assertFalse("the target survived", exists("100-0"));
        assertTrue("gid 1000 was collateral damage", exists("1000-0"));
        assertTrue("gid 1001 was collateral damage", exists("1001-5"));
    }

    /** And a gid the deleted one starts with is just as separate. */
    @Test
    public void keepsGalleriesWhoseGidIsAPrefixOfTheTarget() throws Exception {
        touch("100-0");
        touch("10-0");
        touch("1-7");

        SmbPreviewCache.evictGallery(TARGET_GID);

        assertFalse("the target survived", exists("100-0"));
        assertTrue("gid 10 was collateral damage", exists("10-0"));
        assertTrue("gid 1 was collateral damage", exists("1-7"));
    }

    /** Deleting a gallery whose previews were never fetched is a no-op, not a failure. */
    @Test
    public void toleratesAGalleryWithNothingCached() throws Exception {
        touch("2000-0");

        SmbPreviewCache.evictGallery(TARGET_GID);

        assertTrue("an unrelated gallery was removed", exists("2000-0"));
    }

    /** The cache directory may legitimately not exist yet — that must not throw. */
    @Test
    public void toleratesAMissingCacheDirectory() throws Exception {
        //noinspection ResultOfMethodCallIgnored
        dir.delete();

        SmbPreviewCache.evictGallery(TARGET_GID);
    }
}
