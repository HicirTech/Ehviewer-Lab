package com.hippo.ehviewer.spider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.unifile.UniFile;

import java.io.File;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Pins that a spider worker can follow its queen from reading into downloading (#159). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SpiderDenDownloadReadinessTest {

    private static final long GID = 2795528L;

    private GalleryInfo info;
    private File root;

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        SpiderDen.initialize(RuntimeEnvironment.getApplication());
        EhDB.initialize(RuntimeEnvironment.getApplication());
        root = new File(RuntimeEnvironment.getApplication().getCacheDir(), "phone-downloads");
        // The cache dir can outlive one test.
        deleteTree(root);
        assertTrue(root.mkdirs());
        Settings.putDownloadLocation(UniFile.fromFile(root));
        info = new GalleryInfo();
        info.gid = GID;
        info.token = "0123456789";
        info.title = "readiness fixture";
    }

    @Test
    public void aWorkerStartedForReading_preparesTheFolderOnceTheQueenDownloads() {
        SpiderDen den = new SpiderDen(info);
        den.setMode(SpiderQueen.MODE_READ);
        assertTrue("reading prepares nothing", den.prepareDownloadStorage());
        den.setMode(SpiderQueen.MODE_DOWNLOAD);
        assertFalse("no folder yet: this alone made every such worker quit", den.isReady());

        assertTrue(den.ensureReady());
        assertTrue(den.isReady());
        assertEquals("the gallery's folder now exists", 1, galleryFolders());
    }

    @Test
    public void readingNeverMakesADownloadFolder() {
        SpiderDen den = new SpiderDen(info);
        den.setMode(SpiderQueen.MODE_READ);

        assertTrue(den.ensureReady());

        assertEquals("no folder for this gallery", 0, galleryFolders());
    }

    private int galleryFolders() {
        File[] made = root.listFiles((dir, name) -> name.startsWith(GID + "-"));
        return made == null ? 0 : made.length;
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
