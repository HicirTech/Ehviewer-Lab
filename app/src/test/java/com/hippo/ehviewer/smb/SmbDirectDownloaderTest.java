package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderQueen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import org.robolectric.shadow.api.Shadow;

/**
 * Pins the SMB download task state machine (issue #43).
 *
 * <p>Its bugs are the silent kind: a task that never starts, a gid that can never be enqueued
 * again, or a folder deleted while it is still being written. One of those has already shipped —
 * the {@code clearPending} leak that made every later enqueue a no-op until the app restarted.
 *
 * <p>No production code is modified to make this testable. Robolectric shadows stand in for the
 * fetch engine, the foreground service and the share delete, so what is asserted here is state
 * transitions and call ordering. The SMB target mark is left running for real, since it is plain
 * in-memory state and the marking rules are part of what this pins.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {
                SmbDirectDownloaderTest.ShadowSpiderQueen.class,
                SmbDirectDownloaderTest.ShadowSmbDownloadService.class,
                SmbDirectDownloaderTest.ShadowSmbGalleryLifecycle.class,
        },
        instrumentedPackages = {"com.hippo.ehviewer.spider", "com.hippo.ehviewer.smb"})
public class SmbDirectDownloaderTest {

    private Context context;

    /** Every outside-world call the state machine made, in order. */
    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    /** Makes obtaining a job fail the way a gallery already owned by DownloadManager does. */
    static boolean obtainThrows = false;
    /** Lets a test wait for the folder delete, which runs on the IO pool. */
    static CountDownLatch deleteLatch = new CountDownLatch(1);

    /** Replaces the real fetch engine: no worker thread, no network. */
    @Implements(SpiderQueen.class)
    public static class ShadowSpiderQueen {

        @Resetter
        public static void reset() {
            obtainThrows = false;
        }

        @Implementation
        protected static SpiderQueen obtainSpiderQueen(Context context, GalleryInfo info, int mode) {
            if (obtainThrows) {
                throw new IllegalStateException("a DownloadManager download owns this gallery");
            }
            calls.add("start:" + info.gid);
            return Shadow.newInstanceOf(SpiderQueen.class);
        }

        @Implementation
        protected static void releaseSpiderQueen(SpiderQueen queen, int mode) {
            calls.add("stop");
        }

        // The instance was created without running field initialisers, so the real listener
        // list does not exist; these would NPE.
        @Implementation
        protected void addOnSpiderListener(SpiderQueen.OnSpiderListener listener) {}

        @Implementation
        protected void removeOnSpiderListener(SpiderQueen.OnSpiderListener listener) {}
    }

    /** Keeps the foreground service out of the test. */
    @Implements(SmbDownloadService.class)
    public static class ShadowSmbDownloadService {

        @Implementation
        protected static void start(Context context) {
            calls.add("startService");
        }

        @Implementation
        protected static void stop(Context context) {
            calls.add("stopService");
        }
    }

    /**
     * Only the folder delete is stood in for. Everything else in the SMB layer — notably
     * the SMB target mark — keeps running for real, since it is plain in-memory state and the
     * marking rules are part of what this test pins.
     */
    @Implements(SmbGalleryLifecycle.class)
    public static class ShadowSmbGalleryLifecycle {

        @Implementation
        protected static boolean deleteGalleryFolder(GalleryInfo info) {
            calls.add("delete:" + info.gid);
            deleteLatch.countDown();
            return true;
        }
    }

    private static GalleryInfo gallery(long gid) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = "task fixture " + gid;
        info.pages = 10;
        return info;
    }

    /** Runs whatever the downloader posted to the main thread. */
    private void drain() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private List<SmbDirectDownloader.TaskSnapshot> tasks() {
        return SmbDirectDownloader.getInstance().snapshotTasks();
    }

    private SmbDirectDownloader.TaskSnapshot.State stateOf(long gid) {
        for (SmbDirectDownloader.TaskSnapshot t : tasks()) {
            if (t.gid == gid) {
                return t.state;
            }
        }
        return null;
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        calls.clear();
        obtainThrows = false;
        deleteLatch = new CountDownLatch(1);
    }

    @After
    public void tearDown() {
        // The downloader is a process-wide singleton, so leave no task behind for the next test.
        for (SmbDirectDownloader.TaskSnapshot t : new ArrayList<>(tasks())) {
            SmbDirectDownloader.getInstance().cancel(t.gid);
        }
        drain();
        calls.clear();
    }

    // --- transitions -------------------------------------------------------------------------

    @Test
    public void start_movesTheFirstGalleryStraightToActive() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(1));
        assertTrue(calls.contains("start:1"));
    }

    /** MAX_CONCURRENT is 1: a second gallery waits rather than running alongside. */
    @Test
    public void start_queuesBeyondOneConcurrentJob() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        SmbDirectDownloader.getInstance().start(context, gallery(2));
        drain();

        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(1));
        assertEquals(SmbDirectDownloader.TaskSnapshot.State.QUEUED, stateOf(2));
        assertFalse("the queued gallery must not have been started",
                calls.contains("start:2"));
    }

    @Test
    public void start_isIdempotentForTheSameGallery() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        assertEquals(1, tasks().size());
        assertEquals(1, Collections.frequency(calls, "start:1"));
    }

    @Test
    public void pause_thenResume_returnsTheGalleryToTheQueue() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        SmbDirectDownloader.getInstance().pause(1);
        drain();
        assertEquals(SmbDirectDownloader.TaskSnapshot.State.PAUSED, stateOf(1));
        assertTrue(calls.contains("stop"));

        SmbDirectDownloader.getInstance().resume(1);
        drain();
        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(1));
    }

    @Test
    public void pause_promotesTheNextQueuedGallery() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        SmbDirectDownloader.getInstance().start(context, gallery(2));
        drain();

        SmbDirectDownloader.getInstance().pause(1);
        drain();

        assertEquals(SmbDirectDownloader.TaskSnapshot.State.PAUSED, stateOf(1));
        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(2));
    }

    @Test
    public void cancel_removesTheTaskFromEveryState() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        SmbDirectDownloader.getInstance().start(context, gallery(2));
        drain();
        SmbDirectDownloader.getInstance().pause(2);
        drain();

        SmbDirectDownloader.getInstance().cancel(1);
        SmbDirectDownloader.getInstance().cancel(2);
        drain();

        assertTrue(tasks().isEmpty());
    }

    // --- invariants --------------------------------------------------------------------------

    /**
     * The folder must not be wiped while the job may still be writing to it, so the release has
     * to happen first. The delete runs on the IO pool, hence the latch.
     */
    @Test
    public void cancel_releasesTheJobBeforeDeletingTheFolder() throws Exception {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        SmbDirectDownloader.getInstance().cancel(1);
        drain();

        assertTrue("delete never ran", deleteLatch.await(5, TimeUnit.SECONDS));
        List<String> ordered = new ArrayList<>(calls);
        assertTrue(ordered.indexOf("stop") >= 0);
        assertTrue("folder deleted before the job was released",
                ordered.indexOf("stop") < ordered.indexOf("delete:1"));
    }

    /**
     * Cancelling must clear the SMB routing mark, or a later phone download of the same gallery
     * would be silently re-routed to the share.
     */
    @Test
    public void cancel_clearsTheSmbTargetMark() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();
        assertTrue(SmbSpiderStorage.isGidMarkedSmbTarget(1));

        SmbDirectDownloader.getInstance().cancel(1);
        drain();

        assertFalse(SmbSpiderStorage.isGidMarkedSmbTarget(1));
    }

    /**
     * A natural finish keeps the mark, so reads from Local Inventory still resolve to the share
     * without waiting for a process restart. Deliberately the opposite of cancel.
     */
    @Test
    public void finish_keepsTheSmbTargetMark() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        SmbDirectDownloader.getInstance().cancel(1);
        drain();
        assertFalse(SmbSpiderStorage.isGidMarkedSmbTarget(1));

        SmbSpiderStorage.markGidAsSmbTarget(1);
        assertTrue("a finished download must stay routed to the share",
                SmbSpiderStorage.isGidMarkedSmbTarget(1));
        SmbSpiderStorage.unmarkGidAsSmbTarget(1);
    }

    /**
     * If a DownloadManager download already owns the gallery, startJob throws and the mark must
     * come back off; otherwise that concurrent phone download starts writing to SMB mid-flight.
     */
    @Test
    public void startFailure_leavesNoMarkBehind() {
        obtainThrows = true;

        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        assertFalse(SmbSpiderStorage.isGidMarkedSmbTarget(1));
        assertTrue("a job that never started must not be listed as active", tasks().isEmpty());
    }

    /**
     * A cancelled gallery must be enqueueable again.
     *
     * <p>Note this only covers the downloader's own dedup (its queue/active maps). The related
     * regression that shipped once — {@code SmbAutoDownloadManager.pendingGids} keeping a gid
     * forever, so later auto/manual enqueues silently no-op — is one layer up and is not
     * covered: reaching it means going through {@code enqueueInternal}, which writes metadata
     * to the share. That needs its own seam.
     */
    @Test
    public void cancel_allowsTheGalleryToBeEnqueuedAgain() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();
        SmbDirectDownloader.getInstance().cancel(1);
        drain();

        // Re-enqueuing has to be possible again.
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(1));
    }

    // --- service lifecycle ---------------------------------------------------------------------

    /**
     * The foreground service is what keeps the process alive past UI tear-down, so the first
     * task must bring it up.
     *
     * <p>Neither the shutdown nor the "don't start it twice" behaviour is asserted here. Both
     * hang off {@code service != null}, which is only true once the real
     * {@link SmbDownloadService} has called {@code attachService} on create. Nothing attaches
     * through this seam, so those paths are unreachable and asserting them would only be
     * testing the fake. Covering them needs a Robolectric service, which is a different kind
     * of test from this one.
     */
    @Test
    public void service_startsWithTheFirstTask() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        assertTrue(calls.contains("startService"));
    }
}
