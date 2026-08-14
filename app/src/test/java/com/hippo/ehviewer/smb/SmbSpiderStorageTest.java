package com.hippo.ehviewer.smb;

import com.hippo.ehviewer.storage.GalleryTargets;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** The production half of invariant I4 in #41: a gallery only resolves to an SMB backend while it is marked as an SMB target. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbSpiderStorageTest.ShadowSmbGalleryDirectory.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class SmbSpiderStorageTest {

    static boolean listingConsulted;

    /** Records whether anything asked for the folder's contents. */
    @Implements(SmbGalleryDirectory.class)
    public static class ShadowSmbGalleryDirectory {
        @Implementation
        protected static java.util.Set<String> galleryFilenames(GalleryInfo info) {
            listingConsulted = true;
            return new java.util.HashSet<>();
        }
    }

    private static final long GID = 4035531L;

    private static GalleryInfo info() {
        GalleryInfo info = new GalleryInfo();
        info.gid = GID;
        info.title = "gating fixture";
        return info;
    }

    @After
    public void tearDown() {
        GalleryTargets.unmark(GID);
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
        GalleryTargets.mark(GID);

        assertNotNull(SmbSpiderStorage.createIfTarget(info(), GID));
    }

    /**
     * SmbDirectDownloader unmarks on cancel and when startJob fails; a stale mark would keep
     * routing a later phone download through SMB.
     */
    @Test
    public void unmarkingRemovesTheBackendAgain() {
        GalleryTargets.mark(GID);
        GalleryTargets.unmark(GID);

        assertNull(SmbSpiderStorage.createIfTarget(info(), GID));
    }

    @Test
    public void theMarkIsPerGallery() {
        GalleryTargets.mark(GID);

        GalleryInfo other = new GalleryInfo();
        other.gid = GID + 1;
        assertNull(SmbSpiderStorage.createIfTarget(other, other.gid));
    }

    /**
     * removeImage is a deliberate no-op (#102): its one caller cleans up "the failed download's
     * partial file", but atomic writes leave none — the name it would delete is the previous
     * complete page. The old implementation consulted the listing first; that consult is the
     * mutation this test kills.
     */
    @Test
    public void removeImageTouchesNothing() {
        GalleryTargets.mark(GID);
        listingConsulted = false;

        assertFalse(SmbSpiderStorage.createIfTarget(info(), GID).removeImage(0));

        assertFalse("a no-op must not even ask what the folder contains", listingConsulted);
    }
}
