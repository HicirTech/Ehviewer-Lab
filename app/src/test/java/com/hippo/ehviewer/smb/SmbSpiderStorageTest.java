package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The production half of invariant I4 in #41: a gallery only resolves to an SMB backend while it
 * is marked as an SMB target. SpiderDenRoutingTest pins what the routing does once a backend
 * exists; this pins when one exists at all.
 *
 * <p>The mark is process-global mutable state, so each test clears it again.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbSpiderStorageTest {

    private static final long GID = 4035531L;

    private static GalleryInfo info() {
        GalleryInfo info = new GalleryInfo();
        info.gid = GID;
        info.title = "gating fixture";
        return info;
    }

    @After
    public void tearDown() {
        SmbSpiderStorage.unmarkGidAsSmbTarget(GID);
    }

    /**
     * A regular DownloadManager download must keep writing to phone storage exactly as it did
     * before the SMB work existed.
     */
    @Test
    public void unmarkedGalleryHasNoBackend() {
        assertNull(SmbSpiderStorage.createIfTarget(info(), GID));
    }

    @Test
    public void markedGalleryResolvesToABackend() {
        SmbSpiderStorage.markGidAsSmbTarget(GID);

        assertNotNull(SmbSpiderStorage.createIfTarget(info(), GID));
    }

    /**
     * SmbDirectDownloader unmarks on cancel and when startJob fails; a stale mark would keep
     * routing a later phone download through SMB.
     */
    @Test
    public void unmarkingRemovesTheBackendAgain() {
        SmbSpiderStorage.markGidAsSmbTarget(GID);
        SmbSpiderStorage.unmarkGidAsSmbTarget(GID);

        assertNull(SmbSpiderStorage.createIfTarget(info(), GID));
    }

    @Test
    public void theMarkIsPerGallery() {
        SmbSpiderStorage.markGidAsSmbTarget(GID);

        GalleryInfo other = new GalleryInfo();
        other.gid = GID + 1;
        assertNull(SmbSpiderStorage.createIfTarget(other, other.gid));
    }
}
