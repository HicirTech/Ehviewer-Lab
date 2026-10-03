package com.hippo.ehviewer.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.smb.SmbAutoDownloadManager;
import com.hippo.ehviewer.ui.CommonOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowToast;

/** Pins where a Power Download goes and what it says, given what already exists (#159). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {
                PowerDownloaderTest.ShadowEhApplication.class,
                PowerDownloaderTest.ShadowDownloadManager.class,
                PowerDownloaderTest.ShadowCommonOperations.class,
                PowerDownloaderTest.ShadowSmbAutoDownloadManager.class,
        },
        // Robolectric instruments by name prefix, so one class can be listed on its own.
        instrumentedPackages = {"com.hippo.ehviewer.EhApplication", "com.hippo.ehviewer.download",
                "com.hippo.ehviewer.ui.CommonOperations", "com.hippo.ehviewer.smb"})
public class PowerDownloaderTest {

    private static final long GID = 2793140L;

    /** The phone download list's answer for GID. */
    static int phoneState;
    static final List<Long> phoneStarts = new ArrayList<>();
    static final List<String> shareAsks = new ArrayList<>();

    private Context context;

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
        protected int getDownloadState(long gid) {
            return phoneState;
        }
    }

    @Implements(CommonOperations.class)
    public static class ShadowCommonOperations {
        @Implementation
        protected static void startDownloadWithoutAsking(Context context, GalleryInfo info) {
            phoneStarts.add(info.gid);
        }
    }

    @Implements(SmbAutoDownloadManager.class)
    public static class ShadowSmbAutoDownloadManager {
        @Implementation
        protected void enqueueQuietly(Context context, GalleryInfo info) {
            shareAsks.add("quiet");
        }

        @Implementation
        protected void enqueueManual(Context context, GalleryInfo info) {
            shareAsks.add("manual");
        }
    }

    private static GalleryInfo gallery() {
        GalleryInfo info = new GalleryInfo();
        info.gid = GID;
        info.title = "power download fixture";
        return info;
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
        phoneState = DownloadInfo.STATE_INVALID;
        phoneStarts.clear();
        shareAsks.clear();
    }

    // --- to the phone -------------------------------------------------------------------------

    @Test
    public void phone_aNewGalleryStartsAndSaysSo() {
        PowerDownloader.download(context, gallery(), PowerDownloadTarget.PHONE, true);

        assertEquals(1, phoneStarts.size());
        assertEquals(context.getString(R.string.added_to_download_list), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void phone_aStoppedOrFailedDownloadStartsAgain() {
        phoneState = DownloadInfo.STATE_NONE;
        PowerDownloader.download(context, gallery(), PowerDownloadTarget.PHONE, true);
        phoneState = DownloadInfo.STATE_FAILED;
        PowerDownloader.download(context, gallery(), PowerDownloadTarget.PHONE, true);

        assertEquals(2, phoneStarts.size());
    }

    @Test
    public void phone_aWaitingOrRunningDownloadIsLeftAlone() {
        for (int state : new int[]{DownloadInfo.STATE_WAIT, DownloadInfo.STATE_DOWNLOAD}) {
            phoneState = state;

            PowerDownloader.download(context, gallery(), PowerDownloadTarget.PHONE, true);
            assertEquals("a rule firing mid-read says nothing", 0, ShadowToast.shownToastCount());

            PowerDownloader.download(context, gallery(), PowerDownloadTarget.PHONE, false);
            assertEquals(context.getString(R.string.power_download_already_downloading),
                    ShadowToast.getTextOfLatestToast());
            ShadowToast.reset();
        }
        assertTrue(phoneStarts.isEmpty());
    }

    /** The download list would restart it, re-running its spider, and say "added" again. */
    @Test
    public void phone_aFinishedDownloadIsNotRunAgain() {
        phoneState = DownloadInfo.STATE_FINISH;

        PowerDownloader.download(context, gallery(), PowerDownloadTarget.PHONE, true);
        assertEquals("a rule firing mid-read says nothing", 0, ShadowToast.shownToastCount());

        PowerDownloader.download(context, gallery(), PowerDownloadTarget.PHONE, false);
        assertEquals(context.getString(R.string.power_download_already_downloaded),
                ShadowToast.getTextOfLatestToast());
        assertTrue(phoneStarts.isEmpty());
    }

    // --- to network storage, and nowhere ------------------------------------------------------

    @Test
    public void share_aRuleAsksQuietlyAndATapAsksOutLoud() {
        PowerDownloader.download(context, gallery(), PowerDownloadTarget.NETWORK_STORAGE, true);
        PowerDownloader.download(context, gallery(), PowerDownloadTarget.NETWORK_STORAGE, false);

        assertEquals(Arrays.asList("quiet", "manual"), shareAsks);
        assertTrue(phoneStarts.isEmpty());
    }

    /** A local album opens in the reader like a gallery, but it already is the only copy. */
    @Test
    public void aLocalImportGoesNowhere() {
        DownloadInfo album = new DownloadInfo();
        album.gid = 1727000000001L;
        album.token = "local";
        album.title = "Pictures";
        album.archiveUri = "local-album:content://tree/primary%3APictures";

        PowerDownloader.download(context, album, PowerDownloadTarget.PHONE, false);
        PowerDownloader.download(context, album, PowerDownloadTarget.NETWORK_STORAGE, false);

        assertTrue(phoneStarts.isEmpty());
        assertTrue(shareAsks.isEmpty());
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test
    public void noneGoesNowhere() {
        PowerDownloader.download(context, gallery(), PowerDownloadTarget.NONE, false);

        assertTrue(phoneStarts.isEmpty());
        assertTrue(shareAsks.isEmpty());
        assertEquals(0, ShadowToast.shownToastCount());
    }
}
