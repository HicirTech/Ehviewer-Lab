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

    /** The gallery's folder, created if missing. Writers only. */
    @NonNull
    static SmbFile getGalleryDir(@NonNull GalleryInfo info) throws IOException {
        long t0 = SystemClock.elapsedRealtime();
        CIFSContext cifs = SmbConnection.buildContext();
        SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
        ensureDir(galleryRoot, cifs);
        SmbFile galleryDir = new SmbFile(galleryRoot, SmbPaths.buildGalleryFolderName(info) + "/");
        ensureDir(galleryDir, cifs);
        Log.i("SmbPerf", "getGalleryDir gid=" + info.gid + " " + (SystemClock.elapsedRealtime() - t0) + "ms thr=" + Thread.currentThread().getName());
        return galleryDir;
    }

    /**
     * exists/mkdirs is racy by design at job start (the metadata skeleton and prepareDir run
     * concurrently); the loser of a mkdirs race must not fail the job over a folder that exists.
     */
    private static void ensureDir(@NonNull SmbFile dir, @NonNull CIFSContext cifs)
            throws IOException {
        if (dir.exists()) {
            return;
        }
        try {
            dir.mkdirs();
        } catch (Throwable e) {
            // A fresh SmbFile: the failed instance may hold a cached not-found attribute.
            try {
                if (new SmbFile(dir.getURL().toString(), cifs).exists()) {
                    return;
                }
            } catch (Throwable ignored) {
                // Fall through to the original failure.
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
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
            if (!isFolderMissing(e)) {
                // A transient failure is not a fact about the folder: answer empty this once,
                // but cache nothing — a cached miss makes the whole gallery unreadable for a TTL.
                Log.w(TAG, "list failed gid=" + info.gid, e);
                return names;
            }
            // Genuinely missing folder: empty is the folder's true state; cache it.
        }
        LISTING_CACHE.put(info.gid, names, now);
        return names;
    }

    /** Only a does-not-exist answer may be cached as empty; everything else is weather. */
    private static boolean isFolderMissing(@NonNull Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof jcifs.smb.SmbException) {
                int status = ((jcifs.smb.SmbException) c).getNtStatus();
                return status == jcifs.smb.NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND
                        || status == jcifs.smb.NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND
                        || status == jcifs.smb.NtStatus.NT_STATUS_NO_SUCH_FILE
                        || status == jcifs.smb.NtStatus.NT_STATUS_BAD_NETWORK_NAME;
            }
        }
        return false;
    }

    static void invalidateListing(long gid) {
        LISTING_CACHE.invalidate(gid);
    }

    /** A file the share just confirmed (rename returned): remember it instead of re-listing. */
    static void noteWritten(long gid, @NonNull String name) {
        LISTING_CACHE.noteWritten(gid, name);
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
