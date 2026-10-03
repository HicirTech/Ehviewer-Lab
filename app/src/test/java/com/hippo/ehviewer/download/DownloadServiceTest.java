package com.hippo.ehviewer.download;

import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.ServiceInfo;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

/** Pins what the phone download service does when Android 15 ends its dataSync time (#166). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {
                DownloadServiceTest.ShadowEhApplication.class,
                DownloadServiceTest.ShadowDownloadManager.class,
        },
        instrumentedPackages = {"com.hippo.ehviewer.EhApplication", "com.hippo.ehviewer.download"})
public class DownloadServiceTest {

    static boolean stoppedAll;

    @Implements(EhApplication.class)
    public static class ShadowEhApplication {
        @Implementation
        protected static DownloadManager getDownloadManager(Context context) {
            return Shadow.newInstanceOf(DownloadManager.class);
        }
    }

    /** A download is running until every download is stopped. */
    @Implements(DownloadManager.class)
    public static class ShadowDownloadManager {
        @Implementation
        protected void stopAllDownload() {
            stoppedAll = true;
        }

        @Implementation
        protected boolean isIdle() {
            return stoppedAll;
        }
    }

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        stoppedAll = false;
    }

    @Test
    public void timeout_stopsEveryDownloadAndThenTheService() {
        DownloadService service = Robolectric.buildService(DownloadService.class).create().get();

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);

        assertTrue(stoppedAll);
        assertTrue("the service outlived its timeout", shadowOf(service).isStoppedBySelf());
    }
}
