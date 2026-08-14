package com.hippo.ehviewer.smb;

import com.hippo.ehviewer.storage.DownloadState;
import com.hippo.ehviewer.storage.GalleryTargets;
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

/** Pins what stops a gallery being saved twice, and what stops it being saveable at all (#51). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {
                SmbAutoDownloadManagerTest.ShadowSmbConnection.class,
                SmbAutoDownloadManagerTest.ShadowSmbGalleryLifecycle.class,
                SmbAutoDownloadManagerTest.ShadowSmbInventory.class,
                SmbAutoDownloadManagerTest.ShadowSmbMetadata.class,
                SmbAutoDownloadManagerTest.ShadowSpiderQueen.class,
                SmbAutoDownloadManagerTest.ShadowSmbDownloadService.class,
                SmbAutoDownloadManagerTest.ShadowSmbDownloadStateStore.class,
        },
        instrumentedPackages = {"com.hippo.ehviewer.smb", "com.hippo.ehviewer.spider"})
public class SmbAutoDownloadManagerTest {

    private static final long GID = 4035531L;

    /** One entry per enqueue that got past the gates. */
    static final List<Long> accepted = Collections.synchronizedList(new ArrayList<>());
    /** One entry per download actually begun -- the thing that must not happen twice. */
    static final List<Long> started = Collections.synchronizedList(new ArrayList<>());
    static boolean configured = true;
    static boolean alreadyComplete = false;

    private Context context;

    @Implements(SmbConnection.class)
    public static class ShadowSmbConnection {
        @Implementation
        protected static boolean isConfigured() {
            return configured;
        }
    }

    @Implements(SmbGalleryLifecycle.class)
    public static class ShadowSmbGalleryLifecycle {
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

    @Implements(SmbInventory.class)
    public static class ShadowSmbInventory {
        /** Starting a job now checks whether the gallery already has metadata on the share, so that a download restored or adopted rather than enqueued still get */
        @Implementation
        protected static GalleryInfo readGalleryMetadata(GalleryInfo hint) {
            return hint;   // already there; nothing for startJob to write
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
            started.add(info.gid);
            return Shadow.newInstanceOf(SpiderQueen.class);
        }

        @Implementation
        protected static void releaseSpiderQueen(SpiderQueen queen, int mode) {}

        @Implementation
        protected void addOnSpiderListener(SpiderQueen.OnSpiderListener l) {}

        @Implementation
        protected void removeOnSpiderListener(SpiderQueen.OnSpiderListener l) {}
    }

    /** The enqueue path now asks the share whether another device already claimed the gallery (#59). */
    @Implements(SmbDownloadStateStore.class)
    public static class ShadowSmbDownloadStateStore {

        @Implementation
        protected static List<DownloadState.Published> readAll() {
            return new ArrayList<>();
        }

        @Implementation
        protected static boolean writeSelf(DownloadState.ClientState state) {
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
        info.title = "enqueue fixture";
        info.pages = 10;
        return info;
    }

    /** Long enough that a busy CI runner is not a failure; only a stuck test ever waits this. */
    private static final long PUMP_DEADLINE_MS = 20_000L;
    /** Rounds of nothing arriving before the work is taken to have finished crossing threads. */
    private static final int PUMP_QUIET_ROUNDS = 25;

    /** Lets whatever was handed to a background thread find its way back to the main one. */
    private void pump() {
        long deadline = System.currentTimeMillis() + PUMP_DEADLINE_MS;
        int quiet = 0;
        while (quiet < PUMP_QUIET_ROUNDS && System.currentTimeMillis() < deadline) {
            boolean hadWork = !shadowOf(Looper.getMainLooper()).isIdle();
            shadowOf(Looper.getMainLooper()).idle();
            quiet = hadWork ? 0 : quiet + 1;
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
        Settings.putBoolean(Settings.KEY_NETWORK_STORAGE_ENABLED, true);
        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, true);
        configured = true;
        alreadyComplete = false;
        accepted.clear();
        started.clear();
    }

    @After
    public void tearDown() {
        // Both are process-wide singletons; leave nothing for the next test.
        SmbDirectDownloader.getInstance().cancel(GID);
        pump();
        GalleryTargets.unmark(GID);
        accepted.clear();
        started.clear();
    }

    // --- the enqueue gates, which differ per path --------------------------------------------

    @Test
    public void autoPath_needsBothToggles() {
        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, false);
        SmbAutoDownloadManager.getInstance().enqueueFromFirstPage(context, gallery());
        pump();
        assertTrue("auto-download is off, nothing should have been enqueued", accepted.isEmpty());

        Settings.putBoolean(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED, true);
        Settings.putBoolean(Settings.KEY_NETWORK_STORAGE_ENABLED, false);
        SmbAutoDownloadManager.getInstance().enqueueFromFirstPage(context, gallery());
        pump();
        assertTrue("the master save switch is off", accepted.isEmpty());

        Settings.putBoolean(Settings.KEY_NETWORK_STORAGE_ENABLED, true);
        SmbAutoDownloadManager.getInstance().enqueueFromFirstPage(context, gallery());
        pump();
        assertEquals(1, accepted.size());
    }

    /** The manual path deliberately ignores the auto-download toggle, so the user can save one gallery from the detail screen without turning auto-download o */
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

    // --- one download per gallery, and always saveable again -------------------------------------

    /** Two taps in the same breath, before the first has been through the gates. */
    @Test
    public void twoEnqueuesInFlightAtOnceStillStartOneDownload() {
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertEquals("the same gallery must not be fetched twice", 1, started.size());
    }

    /**
     * Two enqueues, one download. With no local mark left to short-circuit the second, this now
     * rests on the downloader's own queue rather than on remembering what was asked for.
     */
    @Test
    public void enqueuingTwiceStartsOneDownload() {
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertEquals("the gallery must be fetched once, however often it is asked for",
                1, started.size());
        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(GID));
    }

    /** The other half, and the regression this test class exists for: a gallery has to stay saveable. */
    @Test
    public void aGalleryAlreadyOnTheShareCanBeSavedAgainOnceItIsGone() {
        alreadyComplete = true;
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();
        assertNull("nothing should be downloading for a complete gallery", stateOf(GID));
        assertTrue(started.isEmpty());

        alreadyComplete = false;
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertNotNull("the short-circuit left the gallery unsaveable", stateOf(GID));
        assertEquals(1, started.size());
    }

    @Test
    public void aCancelledDownloadCanBeStartedAgain() {
        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();
        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(GID));

        SmbDirectDownloader.getInstance().cancel(GID);
        pump();
        assertNull(stateOf(GID));

        SmbAutoDownloadManager.getInstance().enqueueManual(context, gallery());
        pump();

        assertEquals("cancelling left something behind, so the re-save was dropped",
                SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(GID));
        assertEquals(2, started.size());
    }
}
