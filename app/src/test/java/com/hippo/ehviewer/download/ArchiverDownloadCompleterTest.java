package com.hippo.ehviewer.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.os.Environment;
import android.os.Looper;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.smb.SmbSpiderStorage;
import com.hippo.ehviewer.storage.GalleryTargets;
import com.hippo.unifile.UniFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowEnvironment;

/** Pins where a downloaded archive is imported, and what is left of its zip (#166). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {
                ArchiverDownloadCompleterTest.ShadowEhApplication.class,
                ArchiverDownloadCompleterTest.ShadowDownloadManager.class,
                ArchiverDownloadCompleterTest.ShadowSmbSpiderStorage.class,
        },
        // Robolectric instruments by name prefix, so one class can be listed on its own.
        instrumentedPackages = {"com.hippo.ehviewer.EhApplication", "com.hippo.ehviewer.download",
                "com.hippo.ehviewer.smb.SmbSpiderStorage"})
public class ArchiverDownloadCompleterTest {

    private static final long GID = 3054010L;
    private static final long TASK_ID = 77L;

    /** What the import put on the download list, as gid:state. */
    static final List<String> added = new ArrayList<>();

    private Application app;
    private File downloads;

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
        protected void addLabel(String label) {}

        @Implementation
        protected void addDownload(GalleryInfo info, String label, int state) {
            added.add(info.gid + ":" + state);
        }
    }

    /** The share, ready for any gallery routed to it, without a network round trip. */
    @Implements(SmbSpiderStorage.class)
    public static class ShadowSmbSpiderStorage {
        @Implementation
        protected boolean prepareDir() {
            return true;
        }
    }

    @Before
    public void setUp() throws Exception {
        app = RuntimeEnvironment.getApplication();
        Settings.initialize(app);
        AppConfig.initialize(app);
        // The import names the gallery's folder through the download database.
        EhDB.initialize(app);
        // No all-files access: the app has no folder of its own on shared storage.
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_UNMOUNTED);
        downloads = new File(app.getCacheDir(), "phone-downloads");
        // The cache dir can outlive one test; start from an empty download location.
        deleteTree(downloads);
        assertTrue(downloads.mkdirs());
        Settings.putDownloadLocation(UniFile.fromFile(downloads));
        added.clear();
        // A process-wide singleton: without a fresh one it keeps the first test's application.
        Field instance = ArchiverDownloadCompleter.class.getDeclaredField("sInstance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static GalleryInfo gallery() {
        GalleryInfo info = new GalleryInfo();
        info.gid = GID;
        info.token = "token";
        info.title = "archive fixture";
        return info;
    }

    /** A two-page archive, laid out as the archiver serves one. */
    private static File archive(File dir) throws IOException {
        File zip = new File(dir, "fixture.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String page : new String[]{"01.jpg", "02.jpg"}) {
                out.putNextEntry(new ZipEntry("archive fixture/" + page));
                out.write(page.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return zip;
    }

    private void importArchive(File zip) {
        ArchiverDownloadCompleter.getInstance(app).importDownloadedZip(zip, gallery(), TASK_ID);
    }

    /** Runs the main thread until the import lets go of the zip, which is its last step. */
    private static void awaitZipGone(File zip) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (zip.exists()) {
            assertTrue("the import never let go of " + zip.getName(), System.nanoTime() < deadline);
            Thread.sleep(10);
            shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private String[] galleryFolder() {
        File[] made = downloads.listFiles((dir, name) -> name.startsWith(GID + "-"));
        assertNotNull(made);
        assertEquals("one folder for the gallery", 1, made.length);
        return made[0].list();
    }

    @Test
    public void withoutAllFilesAccess_theArchiveIsImportedAndItsZipDropped() throws Exception {
        File zip = archive(AppConfig.getArchiverDir());

        importArchive(zip);
        awaitZipGone(zip);

        assertEquals(Collections.singletonList(GID + ":" + DownloadInfo.STATE_FINISH), added);
        assertEquals(2, galleryFolder().length);
    }

    @Test
    public void aFailedImport_dropsTheZipToo() throws Exception {
        File zip = new File(AppConfig.getArchiverDir(), "broken.zip");
        Files.write(zip.toPath(), "not a zip".getBytes(StandardCharsets.UTF_8));

        importArchive(zip);
        awaitZipGone(zip);

        assertTrue(added.isEmpty());
    }

    /** Marked by an earlier download to the share, or by the share's list showing it. */
    @Test
    public void aGalleryMarkedForTheShare_isStillImportedToThePhone() throws Exception {
        File zip = archive(AppConfig.getArchiverDir());
        GalleryTargets.mark(GID);
        try {
            importArchive(zip);
            awaitZipGone(zip);
        } finally {
            GalleryTargets.unmark(GID);
        }

        assertEquals(Collections.singletonList(GID + ":" + DownloadInfo.STATE_FINISH), added);
        assertEquals(2, galleryFolder().length);
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        file.delete();
    }
}
