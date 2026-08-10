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
import com.hippo.ehviewer.smb.SmbStorage;
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

/**
 * Pins how {@link SpiderDen} dispatches storage on {@code (mode, remote backend present)} — issue
 * #41.
 *
 * <p>This is the code every upstream merge conflicts in, and two real regressions came out of it:
 * #35 (remote reads had no cache fallback, so pages failed while the share was still uploading)
 * and #30. The invariants below are named so a failure says which one broke.
 *
 * <p>No production code is modified to make this testable: the SMB backend is replaced by
 * {@link ShadowSmbSpiderStorage}, and whether a backend exists at all is driven by the real
 * {@code SmbStorage} target mark. The cache is a real on-disk one under Robolectric's temp dir.
 * No share, no network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = SpiderDenRoutingTest.ShadowSmbSpiderStorage.class,
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

        @Resetter
        public static void reset() {
            calls.clear();
            hasImage = false;
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

        @Implementation
        protected OutputStreamPipe openImageOutputStreamPipe(int index, String extension) {
            calls.add("openImageOutputStreamPipe");
            return null;
        }

        @Implementation
        protected InputStreamPipe openImageInputStreamPipe(int index) {
            calls.add("openImageInputStreamPipe");
            // A page is only readable on the share once it has been uploaded.
            return hasImage ? new ByteArrayPipe("on-share".getBytes(StandardCharsets.UTF_8)) : null;
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

        // A backend exists only while the gallery is marked; this is the real gate.
        SmbStorage.markGidAsSmbTarget(GID);
    }

    @After
    public void tearDown() {
        SmbStorage.unmarkGidAsSmbTarget(GID);
        ShadowSmbSpiderStorage.reset();
    }

    private SpiderDen den() {
        SpiderDen den = new SpiderDen(info);
        den.setMGid(GID);
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

    /** Reading a page to show someone. */
    private static final boolean FOR_READER = false;
    /** The downloader filling the share. */
    private static final boolean FOR_DOWNLOAD = true;

    // --- I1: a fetch for the reader never writes to the share --------------------------------

    /**
     * SmbDirectDownloader owns share persistence end to end. If pages fetched to be looked at were
     * written too, every page anyone glanced at would be uploaded.
     */
    @Test
    public void invariant1_aReaderFetchNeverWritesToTheShare() {
        OutputStreamPipe pipe = den().openOutputStreamPipe(INDEX, "jpg", FOR_READER);

        assertNotNull("the page should still be buffered in the cache", pipe);
        assertFalse("a reader fetch asked the share for a write pipe",
                ShadowSmbSpiderStorage.calls.contains("openImageOutputStreamPipe"));
    }

    @Test
    public void invariant1_holdsEvenWithSyncDownloadWhileReadingOn() {
        Settings.putSyncDownloadWhileReading(true);

        den().openOutputStreamPipe(INDEX, "jpg", FOR_READER);

        assertFalse(ShadowSmbSpiderStorage.calls.contains("openImageOutputStreamPipe"));
    }

    // --- I2: the reader is never sent to the share for a page it already has (#35) -----------

    /**
     * The bug this pins. A download running on the same gallery used to flip the shared den into
     * download mode, and the reader went with it — fetching from the share pages the download had
     * not finished uploading, for a gallery whose pages were sitting in the cache all along.
     *
     * <p>Not "falls back to the cache", which was the previous fix and only helped when the share
     * had nothing at all: a file whose name exists but whose bytes are still being written answers
     * yes, and the reader got a truncated read. Asking at all is the defect, so what is asserted
     * here is that the share is never consulted.
     */
    @Test
    public void invariant2_aReaderFetchDoesNotTouchTheShareWhenTheCacheHasThePage() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = true;   // and it is on the share too, mid-write

        InputStreamPipe pipe = den().openInputStreamPipe(INDEX, FOR_READER);

        assertNotNull("page is in the cache but was not served", pipe);
        assertFalse("the reader was sent to the share for a page it already had", askedShare());
    }

    /** With nothing cached there is nowhere else to go, so the share is fair game. */
    @Test
    public void invariant2_aReaderFetchFallsBackToTheShare() {
        ShadowSmbSpiderStorage.hasImage = true;

        assertNotNull(den().openInputStreamPipe(INDEX, FOR_READER));
        assertTrue(askedShare());
    }

    @Test
    public void invariant2_aReaderFetchIsNullWhenNobodyHasThePage() {
        ShadowSmbSpiderStorage.hasImage = false;

        assertNull(den().openInputStreamPipe(INDEX, FOR_READER));
    }

    /**
     * The other direction, and the reason routing cannot simply prefer the cache everywhere: the
     * downloader reads back what it just wrote to check it. Served the cached copy, the check
     * would pass without ever looking at what reached the share.
     */
    @Test
    public void invariant2_aDownloadFetchReadsTheShareEvenWhenCached() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = true;

        assertNotNull(den().openInputStreamPipe(INDEX, FOR_DOWNLOAD));
        assertTrue("the download read-back was served from the cache", askedShare());
    }

    // --- I3: contain() must not claim un-uploaded pages -------------------------------------

    /**
     * The downloader decides what to fetch from contain(). If a cached-but-not-uploaded page
     * counted as present it would be skipped, leaving the share copy incomplete. Deliberately the
     * opposite direction from I2 — easy to "unify" by accident.
     */
    @Test
    public void invariant3_containIsFalseForDownloadWhenOnlyTheCacheHasThePage() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = false;

        assertFalse(den().contain(INDEX, FOR_DOWNLOAD));
    }

    @Test
    public void invariant3_containIsTrueForDownloadOnceTheShareHasThePage() {
        ShadowSmbSpiderStorage.hasImage = true;

        assertTrue(den().contain(INDEX, FOR_DOWNLOAD));
    }

    @Test
    public void readerContainAcceptsTheCache() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = false;

        assertTrue("the reader may serve a page the share does not have yet",
                den().contain(INDEX, FOR_READER));
    }

    // --- I4: an unmarked gallery never touches a remote backend ------------------------------

    /**
     * Regular DownloadManager downloads must behave exactly as before the SMB work. The gate is
     * the real one: createIfTarget is not shadowed.
     */
    @Test
    public void invariant4_unmarkedGalleryNeverReachesTheBackend() {
        SmbStorage.unmarkGidAsSmbTarget(GID);
        seedCache();

        SpiderDen den = den();
        // Reader-intent only: the download-intent paths fall through to the local download
        // directory, which needs a database this test has no reason to stand up.
        den.openInputStreamPipe(INDEX, FOR_READER);
        den.openOutputStreamPipe(INDEX, "jpg", FOR_READER);
        den.contain(INDEX, FOR_READER);

        assertEquals("[]", ShadowSmbSpiderStorage.calls.toString());
    }

    // --- read-mode source order --------------------------------------------------------------

    @Test
    public void readMode_prefersTheCacheOverTheShare() {
        seedCache();
        ShadowSmbSpiderStorage.hasImage = true;

        assertNotNull(den().openInputStreamPipe(INDEX, FOR_READER));
        assertFalse("cache hit should not have gone to the share", askedShare());
    }

    @Test
    public void readMode_fallsBackToTheShareOnACacheMiss() {
        ShadowSmbSpiderStorage.hasImage = true;

        assertNotNull(den().openInputStreamPipe(INDEX, FOR_READER));
        assertTrue(askedShare());
    }

    /** Spider info follows the same routing as images. */
    @Test
    public void spiderInfo_routesThroughTheBackendWhenPresent() {
        SpiderDen den = den();

        assertNotNull(den.openSpiderInfoOutputStream(".ehviewer"));
        assertTrue(ShadowSmbSpiderStorage.calls.contains("openSpiderInfoOutputStream"));
    }
}
