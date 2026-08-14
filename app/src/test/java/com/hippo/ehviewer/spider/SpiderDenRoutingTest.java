package com.hippo.ehviewer.spider;

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

    /**
     * Stands in for the SMB backend. {@code createIfTarget} is deliberately left alone, so a
     * backend still only appears while the gid is marked and the production gate is what runs.
     */
    @Implements(SmbSpiderStorage.class)
    public static class ShadowSmbSpiderStorage {

        /** Every backend call the routing made, in order. */
        static final List<String> calls = new ArrayList<>();

        /** Whether the share already holds the page being asked for. */
        static boolean hasImage = false;
        /** Whether the share will accept a write at all, for simulating a copy that fails. */
        static boolean writable = true;

        @Resetter
        public static void reset() {
            calls.clear();
            hasImage = false;
            writable = true;
            lastWrite = null;
            lastWriteExtension = null;
        }

        /** What reached the share, as text, or null if nothing did. */
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

        /** What the last write to the share carried, or null if there was none. */
        static ByteArrayOutputStream lastWrite;
        /** The extension the share was asked to store the page under. */
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
            // A page is only readable on the share once it has been uploaded.
            return hasImage ? new ByteArrayPipe("on-share".getBytes(StandardCharsets.UTF_8)) : null;
        }
    }

    /** Minimal writable pipe, so a copy onto the share can be inspected rather than merely counted. */
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

    /** The extension a page is stored under is read from the bytes, and Robolectric's BitmapFactory does not report a MIME type for anything. */
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

    /** Minimal pipe over a byte array; the routing only cares that it is non-null. */
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

        // Robolectric's MimeTypeMap starts empty, and the copy names a page on the share by the
        // extension it derives from the bytes. Without this the copy gives up for a reason that
        // has nothing to do with the routing being pinned here.
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton())
                .addExtensionMimeTypeMapping("jpg", "image/jpeg");

        // Finding the phone's copy asks the download database for the folder name before it falls
        // back to listing. Robolectric gives it a real SQLite file under the temp dir.
        com.hippo.ehviewer.EhDB.initialize(RuntimeEnvironment.getApplication());

        // A backend exists only while the gallery is marked; this is the real gate.
        SmbSpiderStorage.markGidAsSmbTarget(GID);
    }

    @After
    public void tearDown() {
        SmbSpiderStorage.unmarkGidAsSmbTarget(GID);
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

    // --- I1: MODE_READ must never write to the share ----------------------------------------

    /**
     * SmbDirectDownloader owns share persistence end to end. If the reader wrote too, every page
     * viewed would be re-uploaded.
     */
    @Test
    public void invariant1_readModeNeverWritesToTheShare() {
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

    // --- I2: download-mode reads fall back to the cache (#35) --------------------------------

    /** SpiderQueen is a per-gid singleton whose mode is shared, so starting an SMB download flips the reader's den to MODE_DOWNLOAD. */
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

    // --- I3: contain() is true only if the page is on the share ------------------------------
    //
    // The downloader decides what to fetch from contain(), so a page it counts as present is a
    // page it will never fetch. This used to be stated as "a cached page does not count", which
    // was the right rule while the cache could not reach the share: counting it would have left
    // the share copy missing that page.
    //
    // A cached page can be put on the share now, so the rule is stated as what it was always
    // protecting: true means the page is there, whether it already was or this call put it there.
    // A copy that fails must still answer false, or the download skips a page it never wrote.

    /** The direction that keeps the share complete. */
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

    // --- I4: an unmarked gallery never touches a remote backend ------------------------------

    /**
     * Regular DownloadManager downloads must behave exactly as before the SMB work. The gate is
     * the real one: createIfTarget is not shadowed.
     */
    @Test
    public void invariant4_unmarkedGalleryNeverReachesTheBackend() {
        SmbSpiderStorage.unmarkGidAsSmbTarget(GID);
        seedCache();

        SpiderDen den = den(SpiderQueen.MODE_READ);
        den.openInputStreamPipe(INDEX);
        den.openOutputStreamPipe(INDEX, "jpg");
        den.contain(INDEX);

        assertEquals("[]", ShadowSmbSpiderStorage.calls.toString());
    }

    // --- read-mode source order --------------------------------------------------------------

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

    /** Spider info follows the same routing as images. */
    @Test
    public void spiderInfo_routesThroughTheBackendWhenPresent() {
        SpiderDen den = den(SpiderQueen.MODE_DOWNLOAD);

        assertNotNull(den.openSpiderInfoOutputStream(".ehviewer"));
        assertTrue(ShadowSmbSpiderStorage.calls.contains("openSpiderInfoOutputStream"));
    }

    // --- I5: a page this device already has must never be fetched again ----------------------
    //
    // contain() is what the download loop asks before fetching a page. For a share-backed gallery
    // it used to mean only "is it on the share": the cache bridge beside it went through
    // getDownloadDir(), which returns null the moment a remote backend is active, so it could
    // never fire. Two consequences, both fixed by the same clause: reading a new gallery with
    // auto-download on fetched its first pages twice (they land in the cache while the den is
    // still in read mode), and moving a download to the share needed a copy loop of its own.

    /** The copy itself: a cached page, byte for byte, onto the share. */
    @Test
    public void invariant5_aCachedPageCanBePutOnTheShare() {
        seedCache();

        assertTrue(RemotePageBridge.copyFromCacheToRemote(info, INDEX));
        assertEquals("the cached page did not reach the share",
                "in-cache", ShadowSmbSpiderStorage.written());
    }

    /** The cache bridge that existed on paper. A page already fetched must go across, not again. */
    @Test
    public void invariant5_downloadModePutsACachedPageOnTheShare() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = false;

        boolean present = den(SpiderQueen.MODE_DOWNLOAD).contain(INDEX);

        assertTrue("a page sitting in the cache must count as present", present);
        assertEquals("the cached page did not reach the share",
                "in-cache", ShadowSmbSpiderStorage.written());
    }

    /** The phone's own copy is the other such hand, and is what makes a move a download. */
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

    /**
     * Order matters as much as the sources do. The share is asked first, so a page already there is
     * not copied over itself once per pass of the download loop.
     */
    @Test
    public void invariant5_theShareIsAskedBeforeAnythingIsCopied() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = true;

        assertTrue(den(SpiderQueen.MODE_DOWNLOAD).contain(INDEX));

        assertFalse("a page already on the share was written to it again",
                ShadowSmbSpiderStorage.calls.contains("openImageOutputStreamPipe"));
    }

    /**
     * I1 again, from the other side. Reading asks; only downloading moves things. A den in read
     * mode holding a cached page must not push it to the share, or browsing would upload.
     */
    @Test
    public void invariant5_readModeStillCopiesNothing() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = false;

        assertTrue("the cached page should still satisfy a read", den(SpiderQueen.MODE_READ).contain(INDEX));

        assertFalse("read mode wrote to the share",
                ShadowSmbSpiderStorage.calls.contains("openImageOutputStreamPipe"));
    }

    /** Nowhere is nowhere: the download must be told to go and fetch it. */
    @Test
    public void invariant5_nothingIsCopiedWhenThePageIsNowhere() {
        ShadowSmbSpiderStorage.hasImage = false;

        assertFalse(den(SpiderQueen.MODE_DOWNLOAD).contain(INDEX));

        assertNull("something was written for a page nobody has",
                ShadowSmbSpiderStorage.written());
    }

    /** Puts one page of this gallery in phone storage, the way a completed phone download leaves it. */
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
