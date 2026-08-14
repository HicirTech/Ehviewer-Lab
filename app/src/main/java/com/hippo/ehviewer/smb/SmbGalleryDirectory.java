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
 * Where a gallery lives on the share, and the per-gid listing cache. Writers may create the
 * folder ({@link #getGalleryDir}); readers must never touch the share ({@link #resolveGalleryDir}).
 */
public final class SmbGalleryDirectory {

    private static final String TAG = "SmbStorage";

    private SmbGalleryDirectory() {}

    /** A minimal GalleryInfo (gid + title), enough to name the folder. */
    @NonNull
    public static GalleryInfo lookupKey(long gid, @Nullable String title) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = title;
        return info;
    }

    /** The gallery's folder, created if missing. Writers only. */
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
     * The gallery's folder without creating it — zero round trips. Read paths must use this, or
     * every query leaves an empty folder the inventory then lists (#59).
     */
    @NonNull
    static SmbFile resolveGalleryDir(@NonNull GalleryInfo info) throws IOException {
        CIFSContext cifs = SmbConnection.buildContext();
        SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
        return new SmbFile(galleryRoot, SmbPaths.buildGalleryFolderName(info) + "/");
    }

    // Short-lived per-gid folder listing: one list() answers every "is page N saved?" until the
    // TTL lapses or a structural change invalidates it (~7 round trips per page otherwise).
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
            // Missing folder reads as empty; the miss is cached too.
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
