package com.hippo.ehviewer.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.ForegroundServiceStartNotAllowedException;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowService;

/** Pins when the archive downloader and its shared foreground service stop (#166). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = ArchiverDownloaderTest.ShadowEhApplication.class,
        instrumentedPackages = {"com.hippo.ehviewer.EhApplication"})
public class ArchiverDownloaderTest {

    private static final long GID_A = 3054001L;
    private static final long GID_B = 3054002L;

    static OkHttpClient client;

    private final HeldCalls calls = new HeldCalls();
    private Application app;
    private ArchiverDownloader downloader;

    @Implements(EhApplication.class)
    public static class ShadowEhApplication {
        @Implementation
        protected static OkHttpClient getOkHttpClient(Context context) {
            return client;
        }
    }

    /** Holds each enqueued call until the test runs it, so no OkHttp thread races an assertion. */
    private static final class HeldCalls extends AbstractExecutorService {
        final Deque<Runnable> held = new ArrayDeque<>();

        void runNext() {
            held.removeFirst().run();
        }

        @Override
        public void execute(Runnable command) {
            held.addLast(command);
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return new ArrayList<>();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    private static GalleryInfo gallery(long gid) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = "archive fixture " + gid;
        info.token = "token" + gid;
        return info;
    }

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        Settings.initialize(app);
        AppConfig.initialize(app);
        client = new OkHttpClient.Builder()
                .dispatcher(new Dispatcher(calls))
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Server Error")
                        .body(ResponseBody.create(null, ""))
                        .build())
                .build();
        Field instance = ArchiverDownloader.class.getDeclaredField("sInstance");
        instance.setAccessible(true);
        instance.set(null, null);
        downloader = ArchiverDownloader.getInstance(app);
    }

    private long start(long gid) {
        return downloader.start(app, gallery(gid), "https://example.org/archive/" + gid,
                "archive" + gid);
    }

    private List<String> serviceActions() {
        List<String> actions = new ArrayList<>();
        Intent intent;
        while ((intent = shadowOf(app).getNextStartedService()) != null) {
            actions.add(intent.getAction());
        }
        return actions;
    }

    @Test
    public void oneTaskEnding_leavesTheServiceToTheTaskStillDownloading() {
        start(GID_A);
        start(GID_B);
        serviceActions();

        calls.runNext();
        assertFalse("B lost its foreground service when A ended",
                serviceActions().contains(ArchiverDownloadService.ACTION_STOP));

        calls.runNext();
        assertTrue("the last task ended and the service stayed up",
                serviceActions().contains(ArchiverDownloadService.ACTION_STOP));
    }

    @Test
    public void aPausedTask_doesNotKeepTheServiceUp() {
        start(GID_A);
        start(GID_B);
        downloader.pause(GID_B);
        serviceActions();

        calls.runNext();

        assertTrue(serviceActions().contains(ArchiverDownloadService.ACTION_STOP));
    }

    @Implements(Service.class)
    public static class ShadowSpentService extends ShadowService {
        @Override
        @Implementation
        protected void startForeground(int id, Notification notification, int foregroundServiceType) {
            throw new ForegroundServiceStartNotAllowedException(
                    "Time limit already exhausted for foreground service type dataSync");
        }
    }

    @Test
    public void timeout_pausesEveryTaskAndStopsTheService() {
        long taskA = start(GID_A);
        start(GID_B);
        ArchiverDownloadService service =
                Robolectric.buildService(ArchiverDownloadService.class).create().get();

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        assertTrue("the service outlived its timeout", shadowOf(service).isStoppedBySelf());
        assertTrue(downloader.getProgress(GID_A).paused);
        assertTrue(downloader.getProgress(GID_B).paused);
        assertTrue("the pause would not survive the process",
                Settings.getArchiverDownloadPaused(taskA));
    }

    @Test
    @Config(shadows = ShadowSpentService.class)
    public void aRefusedPromotion_leavesNoNotificationThatNoStopCanRemove() {
        ArchiverDownloadService service =
                Robolectric.buildService(ArchiverDownloadService.class).create().get();
        Intent update = new Intent(app, ArchiverDownloadService.class)
                .setAction(ArchiverDownloadService.ACTION_UPDATE)
                .putExtra(ArchiverDownloadService.EXTRA_GID, GID_A)
                .putExtra(ArchiverDownloadService.EXTRA_DOWNLOADED, 10L)
                .putExtra(ArchiverDownloadService.EXTRA_TOTAL, 100L);

        service.onStartCommand(update, 0, 1);
        service.onStartCommand(update, 0, 2);

        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        assertEquals(0, shadowOf(nm).size());
    }
}
