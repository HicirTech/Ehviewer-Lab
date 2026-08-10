package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderQueen;

import java.util.ArrayList;
import java.util.Collections;
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
import org.robolectric.shadow.api.Shadow;

/**
 * Pins the dedup mark {@code SmbAutoDownloadManager} keeps per gallery (issue #51).
 *
 * <p>This is the layer the known regression actually lived in: a gid left in {@code pendingGids}
 * makes every later enqueue a silent no-op until the process restarts — the app looks fine, saves
 * just stop happening. {@code SmbDirectDownloader} calls {@code clearPending} on cancel and on
 * finish precisely to avoid that, and nothing pinned it.
 *
 * <p>{@code pendingGids} is private with no getter, and is deliberately not read here. What
 * matters is the behaviour it produces, so every assertion goes through the public enqueue paths.
 * {@code SmbStorage.isGalleryComplete} runs only inside the enqueue's IO task, which makes it an
 * exact probe for "this enqueue was really accepted".
 *
 * <p>No production code is modified: shadows stand in for the share, the fetch engine and the
 * foreground service, all nested here as the rest of the suite does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {
                SmbAutoDownloadManagerTest.ShadowSmbStorage.class,
                SmbAutoDownloadManagerTest.ShadowSmbMetadata.class,
                SmbAutoDownloadManagerTest.ShadowSpiderQueen.class,
                SmbAutoDownloadManagerTest.ShadowSmbDownloadService.class,
                SmbAutoDownloadManagerTest.ShadowSmbDownloadStateStore.class,
        },
        instrumentedPackages = {"com.hippo.ehviewer.smb", "com.hippo.ehviewer.spider"})
public class SmbAutoDownloadManagerTest {

    private static final long GID = 4035531L;

    /** One entry per enqueue that got past the gates and the dedup mark. */
    static final List<Long> accepted = Collections.synchronizedList(new ArrayList<>());
    static boolean configured = true;
    static boolean alreadyComplete = false;

    private Context context;

    @Implements(SmbStorage.class)
    public static class ShadowSmbStorage {
        @Implementation
        protected static boolean isConfigured() {
            return configured;
        }

        @Implementation
        protected static boolean isGalleryComplete(GalleryInfo info) {
            accepted.add(info.gid);
            return alreadyComplete;
        }

        @Implementation
        protected static boolean deleteGalleryFolder(GalleryInfo info) {
            return true;
        }
    }

    @Implements(SmbMetadata.class)
    public static class ShadowSmbMetadata {
        @Implementation
        protected static boolean writeMetadataSkeleton(GalleryInfo info) {
            return true;
        }
    }

    /** Keeps the real fetch engine out; the downloader itself stays real. */
    @Implements(SpiderQueen.class)
    public static class ShadowSpiderQueen {
        @Implementation
        protected static SpiderQueen obtainSpiderQueen(Context c, GalleryInfo info, int mode) {
            return Shadow.newInstanceOf(SpiderQueen.class);
        }

        @Implementation
        protected static void releaseSpiderQueen(SpiderQueen queen, int mode) {}

        @Implementation
        protected void addOnSpiderListener(SpiderQueen.OnSpiderListener l) {}

        @Implementation
        protected void removeOnSpiderListener(SpiderQueen.OnSpiderListener l) {}
    }

    /**
     * The enqueue path now asks the share whether another device already claimed the gallery (#59).
     * That is not what this test is about, and left unshadowed it would reach for a real connection
     * -- SmbStorage is faked here, so isConfigured() says yes -- and take long enough to outlast
     * the pump. An empty share means nobody else has claimed anything.
     */
    @Implements(SmbDownloadStateStore.class)
    public static class ShadowSmbDownloadStateStore {

        @Implementation
        protected static List<SmbDownloadState.Published> readAll() {
            return new ArrayList<>();
        }

        @Implementation
        protected static boolean writeSelf(SmbDownloadState.ClientState state) {
            return true;
        }
    }

    @Implements(SmbDownloadService.class)
    public static class ShadowSmbDownloadService {
        @Implementation
        protected static void start(Context context) {}

        @Implementation
        protected static void stop(Context context) {}
    }

    private static GalleryInfo gallery() {
        GalleryInfo info = new GalleryInfo();
        info.gid = GID;
        info.title = "pending-mark fixture";
        info.pages = 10;
        return info;
    }

    /** The enqueue hops onto the IO pool and back, so pump both until it settles. */
    private void pump() {
        for (int i = 0; i < 100; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static SmbDirectDownloader.TaskSnapshot.State stateOf(long gid) {
        for (SmbDirectDownloader.TaskSnapshot t : SmbDirectDownloader.getInstance().snapshotTasks()) {
            if (t.gid == gid) {
                return t.state;
            }
        }
        return null;
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
        Settings.putBoolean(Settings.KEY_SMB_SAVE_ENABLED, true);
        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, true);
        configured = true;
        alreadyComplete = false;
        accepted.clear();
        SmbAutoDownloadManager.getInstance().clearPending(GID);
    }

    @After
    public void tearDown() {
        // Both are process-wide singletons; leave nothing for the next test.
        SmbDirectDownloader.getInstance().cancel(GID);
        pump();
        SmbAutoDownloadManager.getInstance().clearPending(GID);
        SmbStorage.unmarkGidAsSmbTarget(GID);
        accepted.clear();
    }

    // --- the enqueue gates, which differ per path --------------------------------------------

    @Test
    public void autoPath_needsBothToggles() {
        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, false);
        SmbAutoDownloadManager.getInstance().enqueueFromFirstPage(context, gallery());
        pump();
        assertTrue("auto-download is off, nothing should have been enqueued", accepted.isEmpty());

        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, true);
        Settings.putBoolean(Settings.KEY_SMB_SAVE_ENABLED, false);
        SmbAutoDownloadManager.getInstance().enqueueFromFirstPage(context, gallery());
        pump();
        assertTrue("the master save switch is off", accepted.isEmpty());

        Settings.putBoolean(Settings.KEY_SMB_SAVE_ENABLED, true);
        SmbAutoDownloadManager.getInstance().enqueueFromFirstPage(context, gallery());
        pump();
        assertEquals(1, accepted.size());
    }

    /**
     * The manual path deliberately ignores the auto-download toggle, so the user can save one
     * gallery from the detail screen without turning auto-download on. Collapsing the two gate
     * checks into one would break that.
     */
    @Test
    public void manualPath_needsOnlyTheSaveToggle() {
        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, false);

        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertEquals("manual save must work with auto-download off", 1, accepted.size());
    }

    @Test
    public void bothPaths_needAConfiguredShare() {
        configured = false;

        SmbAutoDownloadManager.getInstance().enqueueFromFirstPage(context, gallery());
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertTrue(accepted.isEmpty());
    }

    // --- the dedup mark ------------------------------------------------------------------------

    @Test
    public void secondEnqueueWhileStillPendingIsDropped() {
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertEquals("the same gallery must not be enqueued twice", 1, accepted.size());
    }

    @Test
    public void clearPendingReopensEnqueueing() {
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();
        assertEquals(1, accepted.size());

        SmbAutoDownloadManager.getInstance().clearPending(GID);
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertEquals(2, accepted.size());
    }

    /**
     * A gallery already complete on the share short-circuits — and must release its mark on the
     * way out, or re-saving it after the share copy is removed would silently do nothing.
     */
    @Test
    public void alreadyCompleteGalleryReleasesItsMark() {
        alreadyComplete = true;
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();
        assertEquals(1, accepted.size());
        assertNull("nothing should be downloading for a complete gallery", stateOf(GID));

        alreadyComplete = false;
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertEquals("the mark was not released on the complete short-circuit", 2, accepted.size());
        assertNotNull(stateOf(GID));
    }

    /**
     * The regression this issue exists for: cancelling a download has to release the mark, or the
     * gallery can never be enqueued again until the app restarts.
     */
    @Test
    public void cancellingTheDownloadReleasesTheMark() {
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();
        assertEquals(1, accepted.size());
        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(GID));

        SmbDirectDownloader.getInstance().cancel(GID);
        pump();
        assertNull(stateOf(GID));

        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertEquals("cancel left the gid marked pending, so the re-save was dropped",
                2, accepted.size());
        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(GID));
    }
}
