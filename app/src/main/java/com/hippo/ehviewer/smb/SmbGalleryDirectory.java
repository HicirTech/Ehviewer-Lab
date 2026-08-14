package com.hippo.ehviewer.smb;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

/**
 * Where a gallery lives on the share, and what its folder currently holds (#97).
 *
 * <p>Two jobs, deliberately kept together because they answer with the same object: resolving a
 * {@link GalleryInfo} to its {@code <gid>-<title>/} folder (creating it for writers via
 * {@link #getGalleryDir}, never creating it for readers via {@link #resolveGalleryDir}), and the
 * short-lived per-gid listing cache that turns "is page N saved?" from ~7 round-trips into a set
 * lookup. Every class above — file IO, lifecycle, inventory — starts here; this class itself only
 * goes down to {@link SmbConnection} for a context and a root URL.
 *
 * <p>Split out of the old 1494-line {@code SmbStorage}; method bodies are verbatim from there.
 */
public final class SmbGalleryDirectory {

    private static final String TAG = "SmbStorage";

    private SmbGalleryDirectory() {}

    /**
     * Builds a lightweight {@link GalleryInfo} carrying just the fields needed by SMB
     * lookup helpers ({@code gid} + {@code title}). Used from contexts that must avoid
     * holding a back-reference to a full GalleryInfo (e.g. parcelable preview sets).
     */
    @NonNull
    public static GalleryInfo lookupKey(long gid, @Nullable String title) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = title;
        return info;
    }

    // Package-private: SmbMetadata resolves the same per-gallery dir for its writes.
    @NonNull
    static SmbFile getGalleryDir(@NonNull GalleryInfo info) throws IOException {
        long t0 = SystemClock.elapsedRealtime();
        CIFSContext cifs = SmbConnection.buildContext();
        SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
        if (!galleryRoot.exists()) {
            galleryRoot.mkdirs();
        }
        SmbFile galleryDir = new SmbFile(galleryRoot, SmbPaths.buildGalleryFolderName(info) + "/");
        if (!galleryDir.exists()) {
            galleryDir.mkdirs();
        }
        Log.i("SmbPerf", "getGalleryDir gid=" + info.gid + " " + (SystemClock.elapsedRealtime() - t0) + "ms thr=" + Thread.currentThread().getName());
        return galleryDir;
    }

    /**
     * The gallery's folder, without creating it.
     *
     * <p>Unlike {@link #getGalleryDir}, which exists for writers and so calls {@code mkdirs}, this
     * answers for callers that need to know whether a gallery is on the share at all — asking with
     * {@code getGalleryDir} would make the answer yes, and leave an empty folder behind that Local
     * Inventory then lists as a gallery with no pages. That is how this was found: the
     * cross-client claim check in #59 returns after the completeness check, and every blocked
     * enqueue littered the share.
     *
     * <p>Package-private for {@link SmbMetadata}, which re-syncs an existing gallery's record.
     */
    @NonNull
    static SmbFile resolveGalleryDir(@NonNull GalleryInfo info) throws IOException {
        CIFSContext cifs = SmbConnection.buildContext();
        SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
        return new SmbFile(galleryRoot, SmbPaths.buildGalleryFolderName(info) + "/");
    }

    /**
     * Short-lived per-gid snapshot of the gallery folder's file names.
     * {@link SmbGalleryFiles#containImage} and {@code findSmbImageFile} answer "is page N saved?"
     * from this in-memory set instead of doing a {@code getGalleryDir()} + one {@code exists()}
     * per supported extension — i.e. ~7 SMB round-trips — on every single page. That per-page
     * cost is what made opening / scanning a big gallery crawl. One {@code list()} now serves
     * every page check until the TTL lapses or a structural change ({@link #prepareGalleryDir},
     * {@link SmbGalleryFiles#removeImage}, {@link SmbGalleryLifecycle#deleteGalleryFolder},
     * {@link SmbGalleryLifecycle#finalizeDownloadedGallery}) invalidates it.
     */
    private static final GalleryListingCache LISTING_CACHE =
            new GalleryListingCache(GalleryListingCache.DEFAULT_TTL_MS);

    @NonNull
    static Set<String> galleryFilenames(@NonNull GalleryInfo info) {
        long now = SystemClock.elapsedRealtime();
        Set<String> cached = LISTING_CACHE.get(info.gid, now);
        if (cached != null) {
            return cached;
        }
        Set<String> names = new HashSet<>();
        long tList = SystemClock.elapsedRealtime();
        try {
            String[] list = resolveGalleryDir(info).list();
            if (list != null) {
                Collections.addAll(names, list);
            }
            Log.i("SmbPerf", "list gid=" + info.gid + " n=" + names.size() + " " + (SystemClock.elapsedRealtime() - tList) + "ms thr=" + Thread.currentThread().getName());
        } catch (Throwable e) {
            // Folder may not exist yet (gallery not saved) — treat as empty, cache the miss so we
            // don't re-probe a missing dir on every page.
        }
        LISTING_CACHE.put(info.gid, names, now);
        return names;
    }

    static void invalidateListing(long gid) {
        LISTING_CACHE.invalidate(gid);
    }

    public static boolean prepareGalleryDir(@NonNull GalleryInfo info) {
        try {
            getGalleryDir(info);
            invalidateListing(info.gid);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to prepare SMB gallery dir gid=" + info.gid, e);
            return false;
        }
    }
}
