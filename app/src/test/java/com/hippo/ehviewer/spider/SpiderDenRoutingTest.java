package com.hippo.ehviewer.spider;

import com.hippo.ehviewer.storage.GalleryTargets;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.beerbelly.SimpleDiskCache;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbSpiderStorage;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

/** Pins how SpiderDen dispatches storage on (mode, remote backend present) — issue #41. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SpiderDenRoutingTest.ShadowSmbSpiderStorage.class,
                SpiderDenRoutingTest.ShadowMimeAwareBitmapFactory.class},
        instrumentedPackages = "com.hippo.ehviewer.smb")
public class SpiderDenRoutingTest {

    private static final long GID = 4035531L;
    private static final int INDEX = 3;

    private GalleryInfo info;

    /** Leaves createIfTarget real, so a backend exists only while the gid is marked. */
    @Implements(SmbSpiderStorage.class)
    public static class ShadowSmbSpiderStorage {

        static final List<String> calls = new ArrayList<>();

        static boolean hasImage = false;
        static boolean writable = true;

        @Resetter
        public static void reset() {
            calls.clear();
            hasImage = false;
            writable = true;
            lastWrite = null;
            lastWriteExtension = null;
        }

        static String written() {
            return lastWrite == null ? null : new String(lastWrite.toByteArray(), StandardCharsets.UTF_8);
        }

        @Implementation
        protected boolean prepareDir() {
            calls.add("prepareDir");
            return true;
        }

        @Implementation
        protected OutputStream openSpiderInfoOutputStream() {
            calls.add("openSpiderInfoOutputStream");
            return new ByteArrayOutputStream();
        }

        @Implementation
        protected InputStream openSpiderInfoInputStream() {
            calls.add("openSpiderInfoInputStream");
            return new ByteArrayInputStream(new byte[0]);
        }

        @Implementation
        protected boolean containImage(int index) {
            calls.add("containImage");
            return hasImage;
        }

        @Implementation
        protected boolean removeImage(int index) {
            calls.add("removeImage");
            return true;
        }

        static ByteArrayOutputStream lastWrite;
        static String lastWriteExtension;

        @Implementation
        protected OutputStreamPipe openImageOutputStreamPipe(int index, String extension) {
            calls.add("openImageOutputStreamPipe");
            if (!writable) {
                return null;
            }
            lastWriteExtension = extension;
            lastWrite = new ByteArrayOutputStream();
            return new ByteArrayOutPipe(lastWrite);
        }

        @Implementation
        protected InputStreamPipe openImageInputStreamPipe(int index) {
            calls.add("openImageInputStreamPipe");
            return hasImage ? new ByteArrayPipe("on-share".getBytes(StandardCharsets.UTF_8)) : null;
        }
    }

    private static final class ByteArrayOutPipe implements OutputStreamPipe {
        private final ByteArrayOutputStream sink;

        ByteArrayOutPipe(ByteArrayOutputStream sink) {
            this.sink = sink;
        }

        @Override
        public void obtain() {}

        @Override
        public void release() {}

        @Override
        public OutputStream open() {
            return sink;
        }

        @Override
        public void close() {}
    }

    /** Robolectric's BitmapFactory reports no MIME type, and the stored extension is derived from it. */
    @Implements(android.graphics.BitmapFactory.class)
    public static class ShadowMimeAwareBitmapFactory {
        @Implementation
        protected static android.graphics.Bitmap decodeStream(
                InputStream is, android.graphics.Rect outPadding,
                android.graphics.BitmapFactory.Options opts) {
            if (opts != null) {
                opts.outMimeType = "image/jpeg";
            }
            return null;
        }
    }

    private static final class ByteArrayPipe implements InputStreamPipe {
        private final byte[] bytes;

        ByteArrayPipe(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public void obtain() {}

        @Override
        public void release() {}

        @Override
        public InputStream open() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() {}
    }

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putSyncDownloadWhileReading(false);
        SpiderDen.initialize(RuntimeEnvironment.getApplication());

        ShadowSmbSpiderStorage.reset();
        info = new GalleryInfo();
        info.gid = GID;
        info.token = "f47cc446f3";
        info.title = "routing fixture";

        // Robolectric's MimeTypeMap starts empty, and the copy names a page by its extension.
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton())
                .addExtensionMimeTypeMapping("jpg", "image/jpeg");

        // Finding the phone's copy asks the download database for the folder name.
        com.hippo.ehviewer.EhDB.initialize(RuntimeEnvironment.getApplication());

        GalleryTargets.mark(GID);
    }

    @After
    public void tearDown() {
        GalleryTargets.unmark(GID);
        ShadowSmbSpiderStorage.reset();
    }

    private SpiderDen den(int mode) {
        SpiderDen den = new SpiderDen(info);
        den.setMGid(GID);
        den.setMode(mode);
        return den;
    }

    private static SimpleDiskCache cache() {
        try {
            Field f = SpiderDen.class.getDeclaredField("sCache");
            f.setAccessible(true);
            return (SimpleDiskCache) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SpiderDen.sCache moved; update this helper", e);
        }
    }

    private void seedCache() {
        cache().put(EhCacheKeyFactory.getImageKey(GID, INDEX),
                new ByteArrayInputStream("in-cache".getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean askedShare() {
        return ShadowSmbSpiderStorage.calls.contains("openImageInputStreamPipe");
    }

    @Test
    public void invariant1_readModeNeverAsksTheShareForAPageWritePipe() {
        OutputStreamPipe pipe = den(SpiderQueen.MODE_READ).openOutputStreamPipe(INDEX, "jpg");

        assertNotNull("read mode should still buffer the page in the cache", pipe);
        assertFalse("read mode asked the share for a write pipe",
                ShadowSmbSpiderStorage.calls.contains("openImageOutputStreamPipe"));
    }

    @Test
    public void invariant1_holdsEvenWithSyncDownloadWhileReadingOn() {
        Settings.putSyncDownloadWhileReading(true);

        den(SpiderQueen.MODE_READ).openOutputStreamPipe(INDEX, "jpg");

        assertFalse(ShadowSmbSpiderStorage.calls.contains("openImageOutputStreamPipe"));
    }

    @Test
    public void invariant2_downloadModeFallsBackToCacheWhenNotOnShareYet() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = false;

        InputStreamPipe pipe = den(SpiderQueen.MODE_DOWNLOAD).openInputStreamPipe(INDEX);

        assertNotNull("page is in the cache but was not served", pipe);
        assertTrue(askedShare());
    }

    @Test
    public void invariant2_downloadModePrefersTheShareWhenItHasThePage() {
        ShadowSmbSpiderStorage.hasImage = true;

        assertNotNull(den(SpiderQueen.MODE_DOWNLOAD).openInputStreamPipe(INDEX));
        assertTrue(askedShare());
    }

    @Test
    public void invariant2_downloadModeStillReturnsNullWhenNeitherHasThePage() {
        ShadowSmbSpiderStorage.hasImage = false;

        assertNull(den(SpiderQueen.MODE_DOWNLOAD).openInputStreamPipe(INDEX));
    }

    @Test
    public void invariant3_containIsFalseWhenTheCachedPageCannotBeCopiedAcross() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = false;
        ShadowSmbSpiderStorage.writable = false;

        assertFalse("a page that could not be written to the share was counted as present",
                den(SpiderQueen.MODE_DOWNLOAD).contain(INDEX));
    }

    @Test
    public void invariant3_containIsTrueOnceTheShareHasThePage() {
        ShadowSmbSpiderStorage.hasImage = true;

        assertTrue(den(SpiderQueen.MODE_DOWNLOAD).contain(INDEX));
    }

    @Test
    public void readMode_containAcceptsTheCache() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = false;

        assertTrue("the reader may serve a page the share does not have yet",
                den(SpiderQueen.MODE_READ).contain(INDEX));
    }

    @Test
    public void invariant4_unmarkedGalleryNeverReachesTheBackend() {
        GalleryTargets.unmark(GID);
        seedCache();

        SpiderDen den = den(SpiderQueen.MODE_READ);
        den.openInputStreamPipe(INDEX);
        den.openOutputStreamPipe(INDEX, "jpg");
        den.contain(INDEX);

        assertEquals("[]", ShadowSmbSpiderStorage.calls.toString());
    }

    @Test
    public void readMode_prefersTheCacheOverTheShare() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = true;

        assertNotNull(den(SpiderQueen.MODE_READ).openInputStreamPipe(INDEX));
        assertFalse("cache hit should not have gone to the share", askedShare());
    }

    @Test
    public void readMode_fallsBackToTheShareOnACacheMiss() {
        ShadowSmbSpiderStorage.hasImage = true;

        assertNotNull(den(SpiderQueen.MODE_READ).openInputStreamPipe(INDEX));
        assertTrue(askedShare());
    }

    @Test
    public void spiderInfo_routesThroughTheBackendWhenPresent() {
        SpiderDen den = den(SpiderQueen.MODE_DOWNLOAD);

        assertNotNull(den.openSpiderInfoOutputStream(".ehviewer"));
        assertTrue(ShadowSmbSpiderStorage.calls.contains("openSpiderInfoOutputStream"));
    }

    @Test
    public void invariant5_aCachedPageCanBePutOnTheShare() {
        seedCache();

        assertTrue(RemotePageBridge.copyFromCacheToRemote(info, INDEX));
        assertEquals("the cached page did not reach the share",
                "in-cache", ShadowSmbSpiderStorage.written());
    }

    @Test
    public void invariant5_downloadModePutsACachedPageOnTheShare() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = false;

        boolean present = den(SpiderQueen.MODE_DOWNLOAD).contain(INDEX);

        assertTrue("a page sitting in the cache must count as present", present);
        assertEquals("the cached page did not reach the share",
                "in-cache", ShadowSmbSpiderStorage.written());
    }

    @Test
    public void invariant5_downloadModePutsAPhoneCopyOnTheShare() {
        seedPhoneCopy("from-phone");
        ShadowSmbSpiderStorage.hasImage = false;

        boolean present = den(SpiderQueen.MODE_DOWNLOAD).contain(INDEX);

        assertTrue("a page sitting in phone storage must count as present", present);
        assertEquals("the phone's copy did not reach the share",
                "from-phone", ShadowSmbSpiderStorage.written());
        assertEquals("stored under the extension it already had",
                ".jpg", ShadowSmbSpiderStorage.lastWriteExtension);
    }

    @Test
    public void invariant5_theShareIsAskedBeforeAnythingIsCopied() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = true;

        assertTrue(den(SpiderQueen.MODE_DOWNLOAD).contain(INDEX));

        assertFalse("a page already on the share was written to it again",
                ShadowSmbSpiderStorage.calls.contains("openImageOutputStreamPipe"));
    }

    @Test
    public void invariant5_readModeStillCopiesNothing() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = false;

        assertTrue("the cached page should still satisfy a read", den(SpiderQueen.MODE_READ).contain(INDEX));

        assertFalse("read mode wrote to the share",
                ShadowSmbSpiderStorage.calls.contains("openImageOutputStreamPipe"));
    }

    @Test
    public void invariant5_nothingIsCopiedWhenThePageIsNowhere() {
        ShadowSmbSpiderStorage.hasImage = false;

        assertFalse(den(SpiderQueen.MODE_DOWNLOAD).contain(INDEX));

        assertNull("something was written for a page nobody has",
                ShadowSmbSpiderStorage.written());
    }

    private void seedPhoneCopy(String content) {
        java.io.File root = new java.io.File(
                RuntimeEnvironment.getApplication().getCacheDir(), "phone-downloads");
        java.io.File dir = new java.io.File(root, GID + "-routing fixture");
        com.hippo.ehviewer.EhDB.putDownloadDirname(GID, GID + "-routing fixture");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        try (java.io.FileWriter w = new java.io.FileWriter(
                new java.io.File(dir, SpiderDen.generateImageFilename(INDEX, ".jpg")))) {
            w.write(content);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        Settings.putDownloadLocation(com.hippo.unifile.UniFile.fromFile(root));
    }
}
