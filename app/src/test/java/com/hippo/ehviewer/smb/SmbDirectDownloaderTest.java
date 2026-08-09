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
                SmbDirectDownloaderTest.ShadowSmbStorage.class,
                SmbDirectDownloaderTest.ShadowSmbDownloadStateStore.class,
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
     * Only the folder delete is stood in for. Everything else on {@code SmbStorage} — notably
     * the SMB target mark — keeps running for real, since it is plain in-memory state and the
     * marking rules are part of what this test pins.
     */
    @Implements(SmbStorage.class)
    public static class ShadowSmbStorage {

        @Implementation
        protected static boolean deleteGalleryFolder(GalleryInfo info) {
            calls.add("delete:" + info.gid);
            deleteLatch.countDown();
            return true;
        }
    }

    /**
     * Catches what would have gone to {@code state/} on the share (#59), so the published view can
     * be asserted without one. Only the newest matters — each write replaces this device's file.
     */
    @Implements(SmbDownloadStateStore.class)
    public static class ShadowSmbDownloadStateStore {

        @Implementation
        protected static boolean writeSelf(SmbDownloadState.ClientState state) {
            published.set(state);
            return true;
        }

        /** What the share is pretending to hold. */
        @Implementation
        protected static List<SmbDownloadState.Published> readAll() {
            return new ArrayList<>(onShare);
        }
    }

    /** Set by a test to stage what other devices -- and a previous run of this one -- published. */
    static final List<SmbDownloadState.Published> onShare =
            Collections.synchronizedList(new ArrayList<>());

    /** The last state published, or null if nothing has been. */
    static final java.util.concurrent.atomic.AtomicReference<SmbDownloadState.ClientState> published =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static GalleryInfo gallery(long gid) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = "task fixture " + gid;
        info.pages = 10;
        return info;
    }

    private static void setAppContext(Context context) {
        try {
            java.lang.reflect.Field f = SmbDirectDownloader.class.getDeclaredField("appContext");
            f.setAccessible(true);
            f.set(SmbDirectDownloader.getInstance(), context);
        } catch (Exception e) {
            throw new AssertionError("could not latch the app context", e);
        }
    }

    private static void resetRestoreFlag() {
        try {
            java.lang.reflect.Field f =
                    SmbDirectDownloader.class.getDeclaredField("restoreStarted");
            f.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicBoolean)
                    f.get(SmbDirectDownloader.getInstance())).set(false);
        } catch (Exception e) {
            throw new AssertionError("could not reset the restore flag", e);
        }
    }

    /** A state file as some device left it on the share. */
    private static SmbDownloadState.Published onShare(String clientId, boolean alive,
                                                      SmbDownloadState.Task... tasks) {
        return new SmbDownloadState.Published(
                new SmbDownloadState.ClientState(clientId, clientId, java.util.Arrays.asList(tasks)),
                alive);
    }

    private static SmbDownloadState.Task stateTask(long gid, SmbDownloadState.TaskState st,
                                                   long claimedAt) {
        return new SmbDownloadState.Task(gid, "tok" + gid, "restored " + gid, st, 0, 10,
                claimedAt, null);
    }

    private String selfId() {
        return com.hippo.ehviewer.Settings.getSmbClientId();
    }

    /**
     * Waits for a restore to finish.
     *
     * <p>Polling the task list cannot do this: a test that expects nothing to come back would see
     * an empty list before the restore had even started and pass without testing anything. The
     * publisher is single-threaded and FIFO, so a marker submitted behind the restore only runs
     * once the restore has returned.
     */
    private void awaitRestore() {
        try {
            java.lang.reflect.Field f = SmbDirectDownloader.class.getDeclaredField("publisher");
            f.setAccessible(true);
            java.util.concurrent.ExecutorService ex =
                    (java.util.concurrent.ExecutorService) f.get(SmbDirectDownloader.getInstance());
            ex.submit(() -> { }).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("restore did not finish", e);
        }
        drain();   // and then whatever it posted to the main thread
    }

    /** Runs whatever the downloader posted to the main thread. */
    private void drain() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /**
     * Waits for the next publish. Publishing runs on its own thread rather than the looper, so
     * draining the main thread is not enough to see it.
     */
    private SmbDownloadState.ClientState awaitPublished() {
        drain();
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline) {
            SmbDownloadState.ClientState s = published.get();
            if (s != null) {
                return s;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            drain();
        }
        throw new AssertionError("nothing was published within 3s");
    }

    private SmbDownloadState.Task publishedTask(SmbDownloadState.ClientState state, long gid) {
        for (SmbDownloadState.Task t : state.tasks) {
            if (t.gid == gid) {
                return t;
            }
        }
        throw new AssertionError("gallery " + gid + " was not published");
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
        published.set(null);
        onShare.clear();
        // The downloader is a process-wide singleton and restores once per process, so without
        // this only the first test would ever exercise it. Reflection rather than a production
        // seam, the same way SpiderDenRoutingTest reaches SpiderDen.sCache.
        resetRestoreFlag();
        // appContext is latched by whichever start() runs first and then lives on the singleton
        // for the rest of the process, so without setting it here a restore-only test would pass
        // or fail depending on what ran before it.
        setAppContext(context);
        // Publishing short-circuits unless a share is configured, so give it one. Nothing connects
        // to it — the store is shadowed — but isConfigured() reads these two straight from Settings.
        com.hippo.ehviewer.Settings.initialize(context);
        com.hippo.ehviewer.Settings.putString(
                com.hippo.ehviewer.Settings.KEY_SMB_HOST, "10.0.0.1");
        com.hippo.ehviewer.Settings.putString(
                com.hippo.ehviewer.Settings.KEY_SMB_SHARE_NAME, "share");
        com.hippo.ehviewer.Settings.putString(
                com.hippo.ehviewer.Settings.KEY_SMB_DEVICE_NAME, "test device");
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
        assertTrue(SmbStorage.isGidMarkedSmbTarget(1));

        SmbDirectDownloader.getInstance().cancel(1);
        drain();

        assertFalse(SmbStorage.isGidMarkedSmbTarget(1));
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
        assertFalse(SmbStorage.isGidMarkedSmbTarget(1));

        SmbStorage.markGidAsSmbTarget(1);
        assertTrue("a finished download must stay routed to the share",
                SmbStorage.isGidMarkedSmbTarget(1));
        SmbStorage.unmarkGidAsSmbTarget(1);
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

        assertFalse(SmbStorage.isGidMarkedSmbTarget(1));
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

    // --- what this device publishes to the share (#59) --------------------------------------------
    //
    // The point of publishing is that other devices can see it, so what matters is that the file
    // says what this device is actually doing. These read what would have been written.

    /**
     * A claim has to be published before the download starts, or another device can begin the same
     * gallery in the window between deciding to download it and saying so.
     */
    @Test
    public void publishes_theClaimWhenAGalleryIsQueued() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));

        SmbDownloadState.ClientState state = awaitPublished();
        SmbDownloadState.Task t = publishedTask(state, 1);
        assertEquals(SmbDownloadState.TaskState.ACTIVE, t.state);
        assertEquals("task fixture 1", t.title);
        assertTrue("a claim needs a time, or nobody can tell whose is newer", t.claimedAt > 0);
    }

    /** Whoever is looking needs to know which device this is, not just its id. */
    @Test
    public void publishes_thisDevicesIdentity() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));

        SmbDownloadState.ClientState state = awaitPublished();
        assertEquals(com.hippo.ehviewer.Settings.getSmbClientId(), state.clientId);
        assertEquals("test device", state.deviceName);
        assertEquals(SmbDownloadState.SCHEMA_VERSION, state.schemaVersion);
    }

    /** Only one job runs at a time, so the second gallery should show as waiting rather than running. */
    @Test
    public void publishes_queuedAndActiveDistinctly() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        SmbDirectDownloader.getInstance().start(context, gallery(2));

        SmbDownloadState.ClientState state = awaitPublished();
        assertEquals(SmbDownloadState.TaskState.ACTIVE, publishedTask(state, 1).state);
        assertEquals(SmbDownloadState.TaskState.QUEUED, publishedTask(state, 2).state);
    }

    @Test
    public void publishes_pausedTasks() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();
        published.set(null);
        SmbDirectDownloader.getInstance().pause(1);

        assertEquals(SmbDownloadState.TaskState.PAUSED,
                publishedTask(awaitPublished(), 1).state);
    }

    /**
     * A cancelled gallery must stop being claimed. Leaving it listed would have the other devices
     * believe this one is still working on something it has abandoned — and since a live claim
     * blocks them from starting it, nobody would ever download it again.
     */
    @Test
    public void publishes_theRemovalWhenATaskIsCancelled() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();
        published.set(null);
        SmbDirectDownloader.getInstance().cancel(1);

        SmbDownloadState.ClientState state = awaitPublished();
        for (SmbDownloadState.Task t : state.tasks) {
            assertFalse("gallery 1 is still claimed after being cancelled", t.gid == 1);
        }
    }

    /**
     * A gallery queued behind an active job starts nothing, so nothing else would announce it.
     * Without the publish at enqueue its claim would sit unseen until the next heartbeat, and for
     * those twenty seconds another device would be free to start the same download.
     */
    @Test
    public void publishes_aClaimEvenWhenNoJobStarts() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        awaitPublished();                 // gallery 1 is active; only one job runs at a time
        published.set(null);

        SmbDirectDownloader.getInstance().start(context, gallery(2));

        assertEquals(SmbDownloadState.TaskState.QUEUED,
                publishedTask(awaitPublished(), 2).state);
    }

    // --- coming back from the share (#59) ---------------------------------------------------------
    //
    // The queue outlives the process because it lives on the share. What comes back has to be
    // filtered through what everyone else published in the meantime, because a device that was
    // away cannot be told its work was taken over -- it has to notice.

    @Test
    public void restore_bringsBackWhatWasQueued() {
        onShare.add(onShare(selfId(), true,
                stateTask(1, SmbDownloadState.TaskState.QUEUED, 100)));

        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();
        assertEquals(1, tasks().size());

        assertEquals(1, tasks().get(0).gid);
    }

    /** The user paused it deliberately; coming back online is not a reason to override that. */
    @Test
    public void restore_leavesPausedTasksPaused() {
        onShare.add(onShare(selfId(), true,
                stateTask(1, SmbDownloadState.TaskState.PAUSED, 100)));

        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();
        assertEquals(1, tasks().size());

        assertEquals(SmbDirectDownloader.TaskSnapshot.State.PAUSED, stateOf(1));
    }

    /** Nothing is running now, so a download the process died in the middle of is simply waiting. */
    @Test
    public void restore_treatsAnInterruptedDownloadAsWaiting() {
        onShare.add(onShare(selfId(), true,
                stateTask(1, SmbDownloadState.TaskState.ACTIVE, 100)));

        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();
        assertEquals(1, tasks().size());

        assertFalse("it should not come back still marked as running",
                stateOf(1) == SmbDirectDownloader.TaskSnapshot.State.PAUSED);
    }

    /** Somebody else picked it up while we were gone. It is theirs now. */
    @Test
    public void restore_dropsWhatALiveDeviceTookOver() {
        onShare.add(onShare(selfId(), true,
                stateTask(1, SmbDownloadState.TaskState.QUEUED, 100)));
        onShare.add(onShare("other", true,
                stateTask(1, SmbDownloadState.TaskState.ACTIVE, 900)));

        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();
        assertEquals(0, tasks().size());
    }

    /** A dead device's claim takes nothing from us, however recent it looks. */
    @Test
    public void restore_keepsWhatOnlyADeadDeviceClaims() {
        onShare.add(onShare(selfId(), true,
                stateTask(1, SmbDownloadState.TaskState.QUEUED, 100)));
        onShare.add(onShare("other", false,
                stateTask(1, SmbDownloadState.TaskState.ACTIVE, 900)));

        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();
        assertEquals(1, tasks().size());
    }

    /** Another device's queue is not ours to adopt. */
    @Test
    public void restore_ignoresOtherDevicesTasks() {
        onShare.add(onShare("other", true,
                stateTask(7, SmbDownloadState.TaskState.QUEUED, 100)));

        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();

        assertTrue("gallery 7 belongs to another device", tasks().isEmpty());
    }

    @Test
    public void restore_survivesAShareWithNothingOfOurs() {
        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();

        assertTrue(tasks().isEmpty());
    }

    /** Once per process, whichever entry point asks first. */
    @Test
    public void restore_happensOnlyOnce() {
        onShare.add(onShare(selfId(), true,
                stateTask(1, SmbDownloadState.TaskState.QUEUED, 100)));

        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();
        assertEquals(1, tasks().size());
        SmbDirectDownloader.getInstance().cancel(1);
        drain();

        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();
        assertTrue("a cancelled task must not be restored again", tasks().isEmpty());
    }

    /** The claim time is the original one, not the moment of restoring. */
    @Test
    public void restore_keepsTheOriginalClaimTime() {
        onShare.add(onShare(selfId(), true,
                stateTask(1, SmbDownloadState.TaskState.QUEUED, 4242)));

        SmbDirectDownloader.getInstance().ensureRestored();
        awaitRestore();
        assertEquals(1, tasks().size());

        assertEquals(4242L, publishedTask(awaitPublished(), 1).claimedAt);
    }

    /**
     * After cleaning, this device has to say so. Its file still advertises claims it no longer
     * holds, and until it is rewritten those keep other devices from starting the galleries.
     */
    @Test
    public void restore_republishesAfterEverythingWasTakenOver() {
        onShare.add(onShare(selfId(), true,
                stateTask(1, SmbDownloadState.TaskState.QUEUED, 100)));
        onShare.add(onShare("other", true,
                stateTask(1, SmbDownloadState.TaskState.ACTIVE, 900)));

        SmbDirectDownloader.getInstance().ensureRestored();

        assertTrue("our file should have been rewritten empty",
                awaitPublished().tasks.isEmpty());
    }

    // --- not downloading what another device already is -------------------------------------------

    @Test
    public void claimedElsewhere_trueForALiveOtherDevice() {
        onShare.add(onShare("other", true,
                stateTask(5, SmbDownloadState.TaskState.ACTIVE, 100)));

        assertTrue(SmbDirectDownloader.getInstance().isClaimedElsewhere(5));
    }

    @Test
    public void claimedElsewhere_falseForOurOwnClaim() {
        onShare.add(onShare(selfId(), true,
                stateTask(5, SmbDownloadState.TaskState.ACTIVE, 100)));

        assertFalse(SmbDirectDownloader.getInstance().isClaimedElsewhere(5));
    }

    /** Otherwise a device that crashed would make the gallery undownloadable everywhere. */
    @Test
    public void claimedElsewhere_falseForADeadDevice() {
        onShare.add(onShare("other", false,
                stateTask(5, SmbDownloadState.TaskState.ACTIVE, 100)));

        assertFalse(SmbDirectDownloader.getInstance().isClaimedElsewhere(5));
    }

    @Test
    public void claimedElsewhere_falseWhenNobodyHasIt() {
        assertFalse(SmbDirectDownloader.getInstance().isClaimedElsewhere(5));
    }

    // --- the list the download screen shows (#59) -------------------------------------------------
    //
    // One row per gallery across every device, adapted to the type the existing list speaks.

    private SmbTaskInfo sharedTask(long gid) {
        for (SmbTaskInfo t : SmbDirectDownloader.getInstance().snapshotSharedTasks()) {
            if (t.gid == gid) {
                return t;
            }
        }
        throw new AssertionError("gallery " + gid + " is not in the shared list");
    }

    @Test
    public void sharedList_carriesTheOwningDevicesName() {
        onShare.add(onShare("other", true,
                stateTask(5, SmbDownloadState.TaskState.ACTIVE, 100)));

        SmbTaskInfo t = sharedTask(5);
        assertEquals("other", t.deviceName);
        assertFalse("someone else's download is not ours to touch", t.mine);
    }

    @Test
    public void sharedList_marksOurOwnTasksAsOurs() {
        onShare.add(onShare(selfId(), true,
                stateTask(5, SmbDownloadState.TaskState.QUEUED, 100)));

        assertTrue(sharedTask(5).mine);
    }

    /** The adapter draws from DownloadInfo's fields, so they have to be filled in. */
    @Test
    public void sharedList_fillsTheFieldsTheListDrawsFrom() {
        onShare.add(new SmbDownloadState.Published(
                new SmbDownloadState.ClientState("other", "Study phone",
                        java.util.Collections.singletonList(
                                new SmbDownloadState.Task(5, "tok", "a title",
                                        SmbDownloadState.TaskState.ACTIVE, 7, 20, 100, null))),
                true));

        SmbTaskInfo t = sharedTask(5);
        assertEquals("a title", t.title);
        assertEquals("tok", t.token);
        assertEquals(7, t.finished);
        assertEquals(20, t.total);
        assertEquals(20, t.pages);
    }

    @Test
    public void sharedList_mapsRunningToTheDownloadingState() {
        onShare.add(onShare("other", true,
                stateTask(5, SmbDownloadState.TaskState.ACTIVE, 100)));

        assertEquals(com.hippo.ehviewer.dao.DownloadInfo.STATE_DOWNLOAD, sharedTask(5).state);
    }

    @Test
    public void sharedList_mapsWaitingToTheWaitingState() {
        onShare.add(onShare("other", true,
                stateTask(5, SmbDownloadState.TaskState.QUEUED, 100)));

        assertEquals(com.hippo.ehviewer.dao.DownloadInfo.STATE_WAIT, sharedTask(5).state);
    }

    /**
     * A task nobody is beating for is not running, whatever it last said. Drawing it as active
     * would leave an abandoned download looking busy indefinitely.
     */
    @Test
    public void sharedList_showsAnAbandonedTaskAsFailed() {
        onShare.add(onShare("other", false,
                stateTask(5, SmbDownloadState.TaskState.ACTIVE, 100)));

        SmbTaskInfo t = sharedTask(5);
        assertEquals(com.hippo.ehviewer.dao.DownloadInfo.STATE_FAILED, t.state);
        assertFalse(t.ownerAlive);
    }

    /** One row per gallery even when two devices name it; the live claim is the one shown. */
    @Test
    public void sharedList_showsOneRowPerGallery() {
        onShare.add(onShare("dead", false,
                stateTask(5, SmbDownloadState.TaskState.ACTIVE, 900)));
        onShare.add(onShare("live", true,
                stateTask(5, SmbDownloadState.TaskState.QUEUED, 100)));

        assertEquals(1, SmbDirectDownloader.getInstance().snapshotSharedTasks().size());
        assertEquals("live", sharedTask(5).deviceName);
    }

    /** The type is what every action branches on, so it has to survive being held as a DownloadInfo. */
    @Test
    public void sharedList_isRecognisableThroughTheListsOwnType() {
        onShare.add(onShare("other", true,
                stateTask(5, SmbDownloadState.TaskState.ACTIVE, 100)));

        com.hippo.ehviewer.dao.DownloadInfo asPlain = sharedTask(5);
        assertTrue(SmbTaskInfo.isSmb(asPlain));
        assertFalse(SmbTaskInfo.isSmb(new com.hippo.ehviewer.dao.DownloadInfo(5)));
    }

    /** The claim is the device's own; re-queuing the same gallery must not restart its clock. */
    @Test
    public void publishes_aStableClaimTimeAcrossUpdates() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        long first = publishedTask(awaitPublished(), 1).claimedAt;

        published.set(null);
        SmbDirectDownloader.getInstance().start(context, gallery(2));

        assertEquals(first, publishedTask(awaitPublished(), 1).claimedAt);
    }
}
