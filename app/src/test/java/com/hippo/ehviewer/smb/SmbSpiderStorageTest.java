package com.hippo.ehviewer.smb;

import com.hippo.ehviewer.storage.GalleryTargets;
import static org.junit.Assert.assertTrue;
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

/** A gallery resolves to an SMB backend only while it is marked as an SMB target (#41). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbSpiderStorageTest.ShadowSmbGalleryDirectory.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class SmbSpiderStorageTest {

    static boolean listingConsulted;

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

    @Test
    public void unmarkedGalleryHasNoBackend() {
        assertNull(SmbSpiderStorage.createIfTarget(info(), GID));
    }

    @Test
    public void markedGalleryResolvesToABackend() {
        GalleryTargets.mark(GID);

        assertNotNull(SmbSpiderStorage.createIfTarget(info(), GID));
    }

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

    @Test
    public void removeImageConsultsTheListingToDelete() {
        GalleryTargets.mark(GID);
        listingConsulted = false;

        SmbSpiderStorage.createIfTarget(info(), GID).removeImage(0);

        assertTrue("the cleanup must look the published page up", listingConsulted);
    }
}
