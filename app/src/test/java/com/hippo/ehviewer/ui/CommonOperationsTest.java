package com.hippo.ehviewer.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.DownloadManager;
import com.hippo.ehviewer.download.DownloadService;
import com.hippo.lib.yorozuya.collect.LongList;

import java.util.HashSet;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

/** Pins where a download started without asking lands, the reader's way in (#159). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {
                CommonOperationsTest.ShadowEhApplication.class,
                CommonOperationsTest.ShadowDownloadManager.class,
        },
        instrumentedPackages = {"com.hippo.ehviewer.EhApplication", "com.hippo.ehviewer.download"})
public class CommonOperationsTest {

    private static final long GID = 2795528L;

    static boolean listed;
    static final Set<String> labels = new HashSet<>();

    private Application app;

    @Implements(EhApplication.class)
    public static class ShadowEhApplication {
        @Implementation
        protected static DownloadManager getDownloadManager(Context context) {
            return Shadow.newInstanceOf(DownloadManager.class);
        }
    }

    @Implements(DownloadManager.class)
    public static class ShadowDownloadManager {
        @Implementation
        protected boolean containDownloadInfo(long gid) {
            return listed;
        }

        @Implementation
        protected boolean containLabel(String label) {
            return labels.contains(label);
        }
    }

    private static GalleryInfo gallery() {
        GalleryInfo info = new GalleryInfo();
        info.gid = GID;
        info.title = "without asking fixture";
        return info;
    }

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Settings.initialize(app);
        listed = false;
        labels.clear();
    }

    private Intent started() {
        Intent intent = shadowOf(app).getNextStartedService();
        assertNotNull("no download was started", intent);
        return intent;
    }

    @Test
    public void aRememberedLabelThatStillExists_isUsed() {
        labels.add("Later");
        Settings.putHasDefaultDownloadLabel(true);
        Settings.putDefaultDownloadLabel("Later");

        CommonOperations.startDownloadWithoutAsking(app, gallery());

        Intent intent = started();
        assertEquals(DownloadService.ACTION_START, intent.getAction());
        assertEquals("Later", intent.getStringExtra(DownloadService.KEY_LABEL));
    }

    @Test
    public void aRememberedLabelThatWasDeleted_fallsBackToTheDefaultList() {
        Settings.putHasDefaultDownloadLabel(true);
        Settings.putDefaultDownloadLabel("Gone");

        CommonOperations.startDownloadWithoutAsking(app, gallery());

        Intent intent = started();
        assertEquals(DownloadService.ACTION_START, intent.getAction());
        assertNull(intent.getStringExtra(DownloadService.KEY_LABEL));
    }

    @Test
    public void withNoRememberedLabel_theDefaultListTakesIt() {
        labels.add("Later");
        Settings.putHasDefaultDownloadLabel(false);
        Settings.putDefaultDownloadLabel("Later");

        CommonOperations.startDownloadWithoutAsking(app, gallery());

        assertNull(started().getStringExtra(DownloadService.KEY_LABEL));
    }

    @Test
    public void aGalleryAlreadyListed_isRestartedNotAddedAgain() {
        listed = true;

        CommonOperations.startDownloadWithoutAsking(app, gallery());

        Intent intent = started();
        assertEquals(DownloadService.ACTION_START_RANGE, intent.getAction());
        LongList gids = intent.getParcelableExtra(DownloadService.KEY_GID_LIST);
        assertNotNull(gids);
        assertEquals(1, gids.size());
        assertEquals(GID, gids.get(0));
    }
}
