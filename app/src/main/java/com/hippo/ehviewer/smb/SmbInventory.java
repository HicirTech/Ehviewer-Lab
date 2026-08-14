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
 * What is on the share, as a list (#97): enumerating gallery folders and reading their metadata.
 *
 * <p>Two shapes for two costs. {@link #listGalleryRefs} is one directory enumeration — cheap
 * enough to drive the Local Inventory's first paint — and hands back {@link GalleryRef}s whose
 * metadata is then read lazily, row by row, via {@link #readGalleryInfo}. {@link #loadInventory}
 * is the eager whole-library read that every sort mode other than "recently downloaded" needs,
 * fanned out over {@link #inventoryExecutor()} at the configured concurrency. Nothing here is
 * cached locally by design: the share is the only source of truth, and every call asks it.
 *
 * <p>Split out of the old 1494-line {@code SmbStorage}; method bodies are verbatim from there.
 */
public final class SmbInventory {

    private static final String TAG = "SmbStorage";

    /**
     * Reads the whole inventory's metadata concurrently. How many at once is a setting — see
     * {@link SmbConcurrency} for what the cost actually is and how the default was measured.
     *
     * <p>Shared rather than created per call: opening the inventory is something a user does often,
     * and spinning up threads each time to have them idle a moment later is the waste a pool exists
     * to avoid. Daemon threads, so they never hold the process up.
     */
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

    /**
     * The pool the inventory reads on, resized to whatever the setting says now.
     *
     * <p>Package-private so the settings screen's benchmark measures the same pool the app uses,
     * rather than a copy of it that might behave differently.
     */
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

        // The expensive half of the inventory, and until now the unmeasured one: every sort other
        // than "recently downloaded" needs fields that only exist inside metadata.json, so this
        // opens one per gallery before the list can show anything. `reads` is therefore the number
        // that matters — the elapsed time on its own says nothing without knowing how many folders
        // it covered.
        long tLoad = SystemClock.elapsedRealtime();
        int reads = 0;

        // Collect (gallery, metadata.json mtime) entries: the mtime feeds the
        // DOWNLOAD_DATE_DESC ordering and isn't a field on GalleryInfo. Other modes ignore it.
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
                // Same gate as listGalleryRefs, so both orderings agree on which folders are
                // galleries at all. Also saves the exists() round trip below on every foreign
                // directory the share happens to carry.
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
            // Collected in submission order, so the list this builds is the same one the serial
            // version built. It is sorted immediately afterwards anyway, but two orderings that
            // agree before sorting is one less thing for a comparator tie to expose.
            for (java.util.concurrent.Future<SmbSortMode.Entry> f : pending) {
                SmbSortMode.Entry entry;
                try {
                    entry = f.get();
                } catch (Throwable e) {
                    // One unreadable gallery must not lose the other eleven. The serial version
                    // skipped it and carried on; letting the exception out of the loop here would
                    // abandon the whole inventory instead.
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

        // Sorting is in memory and should be nothing next to the reads above; timed separately so
        // that stays a fact rather than an assumption.
        long tSort = SystemClock.elapsedRealtime();
        Collections.sort(entries, mode.comparator());
        Log.i("SmbPerf", "inventory.load mode=" + mode + " n=" + entries.size()
                + " reads=" + reads + " " + (SystemClock.elapsedRealtime() - tLoad) + "ms"
                + " sort=" + (SystemClock.elapsedRealtime() - tSort) + "ms"
                + " thr=" + Thread.currentThread().getName());
        return toGalleryList(entries);
    }

    /**
     * One gallery folder's metadata, or null when the folder carries none.
     *
     * <p>Runs on {@link #INVENTORY_EXECUTOR}, one folder per task, so it must not touch anything
     * this class holds. It does not: the folder handle comes in, the context behind it is jcifs'
     * own pooled one, and nothing here is shared with the other tasks.
     *
     * <p>The {@code exists()} first is deliberate — see the note in {@link #readGalleryInfo} on why
     * skipping it saves nothing measurable. Here it earns its keep twice over, because it also
     * populates the attributes {@link SmbFile#lastModified()} then reads without a second query.
     */
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
     * A gallery folder located on the share but not yet read. {@link #loadInventory} reads every
     * {@code metadata.json} up front before the list can show anything, which is O(folders) SMB
     * round-trips on the first paint. The Local Inventory instead lists these refs once (a single
     * share-root enumeration) and reads each folder's metadata lazily — only for the rows actually
     * scrolled into view (see {@link #readGalleryInfo}).
     *
     * <p>{@link #folderMtime} is the folder's own modification time, which the directory enumeration
     * already carries (no extra round-trip), so it can order the default "recently downloaded first"
     * view without reading a single metadata file. It tracks the last write into the gallery folder,
     * i.e. effectively when the download finished — equivalent to the old {@code metadata.json} mtime
     * for ordering purposes.
     */
    public static final class GalleryRef {
        @NonNull public final String folderName;
        public final long folderMtime;

        public GalleryRef(@NonNull String folderName, long folderMtime) {
            this.folderName = folderName;
            this.folderMtime = folderMtime;
        }
    }

    /**
     * Enumerates the gallery folders on the share in one listing, WITHOUT reading any
     * {@code metadata.json}. Cheap enough to drive the first paint of the Local Inventory; callers
     * read each folder's metadata on demand via {@link #readGalleryInfo}.
     */
    @NonNull
    public static List<GalleryRef> listGalleryRefs() {
        List<GalleryRef> refs = new ArrayList<>();
        if (!SmbConnection.isConfigured()) {
            return refs;
        }
        // Timed here rather than only at the callers. SmbSavedGalleries already reports its own
        // `list=` figure, but that is one caller's view of this; the Local Inventory's first paint
        // goes through the same call and had no number at all.
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
                // type and timestamps are populated by the directory enumeration, so isDirectory()
                // and lastModified() here don't cost extra round-trips.
                if (!child.isDirectory()) {
                    continue;
                }
                String name = trimTrailingSlash(child.getName());
                // Not every directory here is a gallery — see SmbPaths.isGalleryFolderName. Foreign
                // ones used to be counted as galleries, which inflated the page count (a page could
                // come out empty) and cost a wasted round trip each when readGalleryInfo went
                // looking for their metadata.
                if (!SmbPaths.isGalleryFolderName(name)) {
                    continue;
                }
                // Sort key: folder CREATION time, i.e. when the download created the folder.
                // The previous key, lastModified(), gets bumped by reading - persisting the
                // reading progress renames .ehviewer inside the folder, which touches the
                // directory mtime - so any gallery you read jumped to the top of the
                // "recently downloaded" order on the next refresh. createTime comes from the
                // same directory enumeration, so it stays free of extra round-trips.
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

    /**
     * Reads one gallery folder's {@code metadata.json} into a {@link GalleryInfo}. Returns
     * {@code null} when the folder has no parseable metadata. Safe to call off the main thread, one
     * folder at a time, as rows scroll into view.
     */
    @Nullable
    public static GalleryInfo readGalleryInfo(@NonNull GalleryRef ref) {
        if (!SmbConnection.isConfigured()) {
            return null;
        }
        // One line per call, like materialize and preview: this runs once per row as rows scroll
        // into view, so it is the per-row cost, and an average would hide the one folder that takes
        // ten times the rest.
        long t0 = SystemClock.elapsedRealtime();
        try {
            CIFSContext cifs = SmbConnection.buildContext();
            SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
            SmbFile folder = new SmbFile(galleryRoot, ref.folderName + "/");
            SmbFile metadata = new SmbFile(folder, SmbMetadata.METADATA_FILE);
            // The exists() check stays. Opening straight away and reading "not found" off the
            // exception looks like one round trip saved per row, and it was tried: median per-row
            // time went from 34 ms to 33 ms, which is nothing. Whatever this costs, it is not the
            // existence query, so the version with fewer moving parts is the one worth keeping.
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
     * Reads a gallery's {@code metadata.json} given only enough of a {@link GalleryInfo} to name
     * its folder — the gid and title. Returns {@code null} if it is not there or not parseable.
     *
     * <p>Exists for the download list (#59), where a shared task carries the queue's fields and
     * nothing else. Everything a row wants beyond that — category, cover, rating — is already on
     * the share, written as a skeleton the moment a gallery is enqueued, so it is read from there
     * rather than copied into {@code state/} and kept in step.
     *
     * <p>Performs SMB I/O; call from a worker thread, and cache the result.
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
