package com.hippo.ehviewer.smb;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

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
        String folderName = SmbPaths.buildGalleryFolderName(info);
        SmbFile galleryDir = new SmbFile(galleryRoot, folderName + "/");
        try {
            ensureDir(galleryDir, cifs);
        } catch (IOException e) {
            if (ntStatus(e) != jcifs.smb.NtStatus.NT_STATUS_OBJECT_NAME_INVALID) {
                throw e;
            }
            createByRename(galleryRoot, galleryDir, info.gid, cifs);
            // A fresh handle: the one above has cached that the folder is missing.
            galleryDir = new SmbFile(galleryRoot, folderName + "/");
        }
        Log.i("SmbPerf", "getGalleryDir gid=" + info.gid + " " + (SystemClock.elapsedRealtime() - t0) + "ms thr=" + Thread.currentThread().getName());
        return galleryDir;
    }

    /** UNAS refuses a long name when creating over SMB but takes it in a rename (#196). */
    private static void createByRename(@NonNull SmbFile root, @NonNull SmbFile dir, long gid,
                                       @NonNull CIFSContext cifs) throws IOException {
        SmbFile temp = new SmbFile(root, SmbTempFiles.nameFor("g" + gid) + "/");
        temp.mkdir();
        try {
            // One-arg renameTo: refuses rather than replaces a folder another device just made.
            temp.renameTo(dir);
            Log.i(TAG, "Created the folder of gid=" + gid + " by renaming a short one");
        } catch (IOException e) {
            SmbTempFiles.delete(temp);
            if (!new SmbFile(dir.getURL().toString(), cifs).exists()) {
                throw e;
            }
        }
    }

    /** Callers race on mkdirs at job start; the loser must not fail over an existing folder. */
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
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /** Never creates the folder: a read path that did would leave empty galleries behind. */
    @NonNull
    static SmbFile resolveGalleryDir(@NonNull GalleryInfo info) throws IOException {
        CIFSContext cifs = SmbConnection.buildContext();
        SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
        return new SmbFile(galleryRoot, SmbPaths.buildGalleryFolderName(info) + "/");
    }

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
            names = storedNames(resolveGalleryDir(info));
            Log.i("SmbPerf", "list gid=" + info.gid + " n=" + names.size() + " " + (SystemClock.elapsedRealtime() - tList) + "ms thr=" + Thread.currentThread().getName());
        } catch (Throwable e) {
            if (!isFolderMissing(e)) {
                // Transient: caching this empty answer would hide the gallery for a TTL.
                Log.w(TAG, "list failed gid=" + info.gid, e);
                return names;
            }
        }
        LISTING_CACHE.put(info.gid, names, now);
        return names;
    }

    /** The folder's file names, empty files left out: a page stored as 0 bytes is not stored (#203). */
    @NonNull
    static Set<String> storedNames(@NonNull SmbFile dir) throws IOException {
        Set<String> names = new HashSet<>();
        SmbFile[] files = dir.listFiles();
        if (files == null) {
            return names;
        }
        for (SmbFile file : files) {
            // Attributes ride the enumeration: no extra round trips.
            if (!file.isDirectory() && file.length() > 0) {
                names.add(file.getName());
            }
        }
        return names;
    }

    private static boolean isFolderMissing(@NonNull Throwable e) {
        int status = ntStatus(e);
        return status == jcifs.smb.NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND
                || status == jcifs.smb.NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND
                || status == jcifs.smb.NtStatus.NT_STATUS_NO_SUCH_FILE
                || status == jcifs.smb.NtStatus.NT_STATUS_BAD_NETWORK_NAME;
    }

    /** The first SmbException's status in the cause chain; 0 when there is none. */
    private static int ntStatus(@NonNull Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof jcifs.smb.SmbException) {
                return ((jcifs.smb.SmbException) c).getNtStatus();
            }
        }
        return 0;
    }

    static void invalidateListing(long gid) {
        LISTING_CACHE.invalidate(gid);
    }

    /** Only for a name the share just confirmed. */
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
