package com.hippo.ehviewer.smb;

import com.hippo.ehviewer.storage.GalleryTargets;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Looper;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.ehviewer.storage.NetworkStorageSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowService;

/** Pins the SMB download task state machine (issue #43). */
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

    static final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    static boolean obtainThrows = false;
    static CountDownLatch deleteLatch = new CountDownLatch(1);
    static final List<SpiderQueen.OnSpiderListener> listeners = new ArrayList<>();
    static boolean spent = false;

    private ServiceController<SmbDownloadService> service;

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

        // Built by Shadow.newInstanceOf without field initialisers: the real listener list is null.
        @Implementation
        protected void addOnSpiderListener(SpiderQueen.OnSpiderListener listener) {
            listeners.add(listener);
        }

        @Implementation
        protected void removeOnSpiderListener(SpiderQueen.OnSpiderListener listener) {}
    }

    @Implements(SmbDownloadService.class)
    public static class ShadowSmbDownloadService extends ShadowService {

        @Implementation
        protected static void start(Context context) {
            calls.add("startService");
        }

        @Implementation
        protected static void stop(Context context) {
            calls.add("stopService");
        }

        @Override
        protected void startForeground(int id, Notification notification, int foregroundServiceType) {
            if (spent) {
                throw new ForegroundServiceStartNotAllowedException(
                        "Time limit already exhausted for foreground service type dataSync");
            }
            super.startForeground(id, notification, foregroundServiceType);
        }
    }

    @Implements(SmbGalleryLifecycle.class)
    public static class ShadowSmbGalleryLifecycle {

        @Implementation
        protected static boolean deleteGalleryFolder(GalleryInfo info) {
            calls.add("delete:" + info.gid);
            deleteLatch.countDown();
            return true;
        }

        @Implementation
        protected static void finalizeDownloadedGallery(Context context, GalleryInfo info) {
            calls.add("finalize:" + info.gid);
        }
    }

    private static GalleryInfo gallery(long gid) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = "task fixture " + gid;
        info.pages = 10;
        return info;
    }

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
        com.hippo.ehviewer.Settings.initialize(context);
        com.hippo.ehviewer.Settings.putString(NetworkStorageSettings.KEY_SMB_HOST, "192.0.2.7");
        com.hippo.ehviewer.Settings.putString(NetworkStorageSettings.KEY_SMB_SHARE_NAME, "share");
        com.hippo.ehviewer.Settings.putBoolean(NetworkStorageSettings.KEY_ENABLED, true);
        calls.clear();
        listeners.clear();
        obtainThrows = false;
        spent = false;
        deleteLatch = new CountDownLatch(1);
    }

    @After
    public void tearDown() {
        // An attached service would stop the next test's first task from starting one.
        if (service != null) {
            service.destroy();
            service = null;
        }
        for (SmbDirectDownloader.TaskSnapshot t : new ArrayList<>(tasks())) {
            SmbDirectDownloader.getInstance().cancel(t.gid);
        }
        drain();
        calls.clear();
    }

    @Test
    public void start_movesTheFirstGalleryStraightToActive() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(1));
        assertTrue(calls.contains("start:1"));
    }

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

    @Test
    public void cancel_clearsTheSmbTargetMark() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();
        assertTrue(GalleryTargets.isMarked(1));

        SmbDirectDownloader.getInstance().cancel(1);
        drain();

        assertFalse(GalleryTargets.isMarked(1));
    }

    @Test
    public void finish_keepsTheSmbTargetMark() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();
        assertTrue(GalleryTargets.isMarked(1));
        assertEquals(1, listeners.size());

        listeners.get(0).onFinish(10, 10, 10);
        drain();

        assertTrue("a finished download must stay routed to the share",
                GalleryTargets.isMarked(1));
        assertTrue("the queen must be released: " + calls, calls.contains("stop"));
        GalleryTargets.unmark(1);
    }

    @Test
    public void startFailure_leavesNoMarkBehind() {
        obtainThrows = true;

        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        assertFalse(GalleryTargets.isMarked(1));
        assertTrue("a job that never started must not be listed as active", tasks().isEmpty());
    }

    @Test
    public void cancel_allowsTheGalleryToBeEnqueuedAgain() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();
        SmbDirectDownloader.getInstance().cancel(1);
        drain();

        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        assertEquals(SmbDirectDownloader.TaskSnapshot.State.ACTIVE, stateOf(1));
    }

    @Test
    public void service_startsWithTheFirstTask() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        drain();

        assertTrue(calls.contains("startService"));
    }

    @Test
    public void timeout_holdsEveryTaskAndStopsTheService() {
        SmbDirectDownloader.getInstance().start(context, gallery(1));
        SmbDirectDownloader.getInstance().start(context, gallery(2));
        drain();
        service = Robolectric.buildService(SmbDownloadService.class).create();

        service.get().onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        drain();

        assertEquals(SmbDirectDownloader.TaskSnapshot.State.PAUSED, stateOf(1));
        assertEquals("the queued gallery must not start in its place",
                SmbDirectDownloader.TaskSnapshot.State.PAUSED, stateOf(2));
        assertTrue("the queen must be released: " + calls, calls.contains("stop"));
        assertTrue("the service outlived its timeout", shadowOf(service.get()).isStoppedBySelf());
    }

    @Test
    public void aRefusedPromotion_isSurvivedAndLeavesNoNotificationBehind() {
        spent = true;
        service = Robolectric.buildService(SmbDownloadService.class).create();
        service.get().updateNotification("title", "text", 10, 3, false);

        service.destroy();
        service = null;

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        assertEquals(0, shadowOf(nm).size());
    }
}
