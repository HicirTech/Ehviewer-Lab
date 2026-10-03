package com.hippo.ehviewer.download;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

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
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Pins when the archive downloader lets its shared foreground service go (#166). */
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
        // Every call fails at once, which ends its task the way a dead link would.
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
        // A process-wide singleton: without a fresh one it keeps the first test's application.
        Field instance = ArchiverDownloader.class.getDeclaredField("sInstance");
        instance.setAccessible(true);
        instance.set(null, null);
        downloader = ArchiverDownloader.getInstance(app);
    }

    private void start(long gid) {
        downloader.start(app, gallery(gid), "https://example.org/archive/" + gid, "archive" + gid);
    }

    /** The actions of every service start since the last call. */
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
}
