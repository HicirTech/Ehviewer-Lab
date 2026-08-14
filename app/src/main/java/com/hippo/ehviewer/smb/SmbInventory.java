package com.hippo.ehviewer.smb;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.data.GalleryInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

/**
 * The share as a list: cheap lazy refs ({@link #listGalleryRefs}) for first paint, and the
 * eager pooled whole-library read ({@link #loadInventory}) sorts need. Nothing cached locally
 * by design — every call asks the share.
 */
public final class SmbInventory {

    private static final String TAG = "SmbStorage";

    // Shared daemon pool for metadata reads; size follows the SmbConcurrency setting.
    private static final java.util.concurrent.ThreadPoolExecutor INVENTORY_EXECUTOR =
            new java.util.concurrent.ThreadPoolExecutor(
                    SmbConcurrency.DEFAULT_METADATA, SmbConcurrency.DEFAULT_METADATA,
                    10L, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "smb-inventory-read");
                        t.setDaemon(true);
                        return t;
                    });

    static {
        INVENTORY_EXECUTOR.allowCoreThreadTimeOut(true);
    }

    /** The pool, resized to the current setting. Package-private so the benchmark measures it. */
    @NonNull
    static java.util.concurrent.ThreadPoolExecutor inventoryExecutor() {
        SmbConcurrency.resize(INVENTORY_EXECUTOR, SmbConcurrency.metadata());
        return INVENTORY_EXECUTOR;
    }

    private SmbInventory() {}

    @NonNull
    public static List<GalleryInfo> loadInventory() {
        return loadInventory(SmbSortMode.DOWNLOAD_DATE_DESC);
    }

    @NonNull
    public static List<GalleryInfo> loadInventory(@NonNull SmbSortMode mode) {
        if (!SmbConnection.isConfigured()) {
            return new ArrayList<>();
        }

        long tLoad = SystemClock.elapsedRealtime();
        int reads = 0;
        // The mtime rides along for DOWNLOAD_DATE_DESC; other modes ignore it.
        List<SmbSortMode.Entry> entries = new ArrayList<>();
        try {
            CIFSContext cifs = SmbConnection.buildContext();
            SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
            if (!galleryRoot.exists() || !galleryRoot.isDirectory()) {
                return new ArrayList<>();
            }
            SmbFile[] children = galleryRoot.listFiles();
            if (children == null) {
                return new ArrayList<>();
            }
            List<SmbFile> folders = new ArrayList<>();
            for (SmbFile child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                // Same gate as listGalleryRefs, so both agree on what counts as a gallery.
                if (!SmbPaths.isGalleryFolderName(trimTrailingSlash(child.getName()))) {
                    continue;
                }
                folders.add(child);
            }

            java.util.concurrent.ThreadPoolExecutor pool = inventoryExecutor();
            List<java.util.concurrent.Future<SmbSortMode.Entry>> pending =
                    new ArrayList<>(folders.size());
            for (SmbFile child : folders) {
                pending.add(pool.submit(() -> readEntry(child)));
            }
            // Collected in submission order so comparator ties stay deterministic.
            for (java.util.concurrent.Future<SmbSortMode.Entry> f : pending) {
                SmbSortMode.Entry entry;
                try {
                    entry = f.get();
                } catch (Throwable e) {
                    // One unreadable gallery must not lose the rest.
                    Log.w(TAG, "Skipping a gallery whose metadata could not be read", e);
                    continue;
                }
                if (entry != null) {
                    reads++;
                    entries.add(entry);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load SMB inventory", e);
            Log.w("SmbPerf", "inventory.load mode=" + mode + " reads=" + reads
                    + " FAILED after " + (SystemClock.elapsedRealtime() - tLoad) + "ms thr="
                    + Thread.currentThread().getName());
            // Return whatever was collected before the failure, in insertion order.
            return toGalleryList(entries);
        }

        long tSort = SystemClock.elapsedRealtime();
        Collections.sort(entries, mode.comparator());
        Log.i("SmbPerf", "inventory.load mode=" + mode + " n=" + entries.size()
                + " reads=" + reads + " " + (SystemClock.elapsedRealtime() - tLoad) + "ms"
                + " sort=" + (SystemClock.elapsedRealtime() - tSort) + "ms"
                + " thr=" + Thread.currentThread().getName());
        return toGalleryList(entries);
    }

    /** One folder's metadata, or null. Runs pooled; shares nothing with this class. */
    @Nullable
    private static SmbSortMode.Entry readEntry(@NonNull SmbFile folder) throws IOException {
        SmbFile metadata = new SmbFile(folder, SmbMetadata.METADATA_FILE);
        if (!metadata.exists()) {
            return null;
        }
        String json;
        try (InputStream is = metadata.getInputStream()) {
            json = SmbGalleryFiles.readAll(is);
        }
        JSONObject object = JSONObject.parseObject(json);
        if (object == null) {
            return null;
        }
        GalleryInfo info = GalleryInfo.galleryInfoFromJson(object);
        long mtime;
        try {
            mtime = metadata.lastModified();
        } catch (Throwable ignored) {
            mtime = 0L;
        }
        return new SmbSortMode.Entry(info, mtime);
    }

    @NonNull
    private static List<GalleryInfo> toGalleryList(@NonNull List<SmbSortMode.Entry> entries) {
        List<GalleryInfo> out = new ArrayList<>(entries.size());
        for (SmbSortMode.Entry e : entries) {
            out.add(e.info);
        }
        return out;
    }

    /**
     * A folder located but not yet read; metadata is read lazily per visible row. folderMtime
     * rides the enumeration for free and orders the default view without a single metadata read.
     */
    public static final class GalleryRef {
        @NonNull public final String folderName;
        public final long folderMtime;

        public GalleryRef(@NonNull String folderName, long folderMtime) {
            this.folderName = folderName;
            this.folderMtime = folderMtime;
        }
    }

    /** All gallery folders in one listing, no metadata reads — first-paint cheap. */
    @NonNull
    public static List<GalleryRef> listGalleryRefs() {
        List<GalleryRef> refs = new ArrayList<>();
        if (!SmbConnection.isConfigured()) {
            return refs;
        }
        long t0 = SystemClock.elapsedRealtime();
        try {
            CIFSContext cifs = SmbConnection.buildContext();
            SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
            if (!galleryRoot.exists() || !galleryRoot.isDirectory()) {
                return refs;
            }
            SmbFile[] children = galleryRoot.listFiles();
            if (children == null) {
                return refs;
            }
            for (SmbFile child : children) {
                // Attributes ride the enumeration; these calls cost no extra round-trips.
                if (!child.isDirectory()) {
                    continue;
                }
                String name = trimTrailingSlash(child.getName());
                // Foreign folders are not galleries; counting them inflated the page count.
                if (!SmbPaths.isGalleryFolderName(name)) {
                    continue;
                }
                // createTime, not mtime: persisting reading progress bumps mtime and re-sorted
                // whatever you read to the top.
                long mtime;
                try {
                    mtime = child.createTime();
                } catch (Throwable ignored) {
                    mtime = 0L;
                }
                if (mtime == 0L) {
                    try {
                        mtime = child.lastModified();
                    } catch (Throwable ignored) {
                        mtime = 0L;
                    }
                }
                refs.add(new GalleryRef(name, mtime));
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to list SMB gallery folders", e);
        }
        Log.i("SmbPerf", "inventory.refs n=" + refs.size() + " "
                + (SystemClock.elapsedRealtime() - t0) + "ms thr="
                + Thread.currentThread().getName());
        return refs;
    }

    /** One folder's metadata.json as a GalleryInfo, or null. Off the main thread. */
    @Nullable
    public static GalleryInfo readGalleryInfo(@NonNull GalleryRef ref) {
        if (!SmbConnection.isConfigured()) {
            return null;
        }
        long t0 = SystemClock.elapsedRealtime();
        try {
            CIFSContext cifs = SmbConnection.buildContext();
            SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
            SmbFile folder = new SmbFile(galleryRoot, ref.folderName + "/");
            SmbFile metadata = new SmbFile(folder, SmbMetadata.METADATA_FILE);
            // Dropping this exists() was measured: 34→33ms per row, not worth it.
            if (!metadata.exists()) {
                Log.i("SmbPerf", "inventory.info " + ref.folderName + " missing "
                        + (SystemClock.elapsedRealtime() - t0) + "ms thr="
                        + Thread.currentThread().getName());
                return null;
            }
            String json;
            try (InputStream is = metadata.getInputStream()) {
                json = SmbGalleryFiles.readAll(is);
            }
            JSONObject object = JSONObject.parseObject(json);
            if (object == null) {
                return null;
            }
            GalleryInfo info = GalleryInfo.galleryInfoFromJson(object);
            Log.i("SmbPerf", "inventory.info gid=" + info.gid + " bytes=" + json.length()
                    + " " + (SystemClock.elapsedRealtime() - t0) + "ms thr="
                    + Thread.currentThread().getName());
            return info;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to read SMB gallery metadata: " + ref.folderName, e);
            Log.w("SmbPerf", "inventory.info " + ref.folderName + " EXCEPTION after "
                    + (SystemClock.elapsedRealtime() - t0) + "ms: " + e);
            return null;
        }
    }

    /**
     * Metadata by gid+title alone — the download list's row fields (#59) come from the gallery's
     * own record, not a copy in state/. Worker thread; cache the result.
     */
    @Nullable
    public static GalleryInfo readGalleryMetadata(@NonNull GalleryInfo hint) {
        if (!SmbConnection.isConfigured()) {
            return null;
        }
        try {
            SmbFile metadata = new SmbFile(SmbGalleryDirectory.resolveGalleryDir(hint), SmbMetadata.METADATA_FILE);
            if (!metadata.exists()) {
                return null;
            }
            String json;
            try (InputStream is = metadata.getInputStream()) {
                json = SmbGalleryFiles.readAll(is);
            }
            JSONObject object = JSONObject.parseObject(json);
            return object == null ? null : GalleryInfo.galleryInfoFromJson(object);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read metadata for gid=" + hint.gid, e);
            return null;
        }
    }

    /** jcifs reports directory names with a trailing slash; the gallery folder name has none. */
    @NonNull
    private static String trimTrailingSlash(@NonNull String name) {
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }
}
