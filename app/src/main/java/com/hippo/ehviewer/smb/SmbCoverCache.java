/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fetches the Local Inventory's covers ahead of the list, several at a time.
 *
 * <p>Conaco loads images on a serial executor, one after another, so a screen of covers on a share
 * is a queue rather than a fan-out. That is the same problem {@link SmbPreviewCache} was written to
 * solve for the preview grid, and this is the same answer for the list: pull the covers in parallel
 * into local files, and let Conaco read a local file when it gets there.
 *
 * <p>Measured before this existed, twelve covers on a real NAS: 456 ms finding them plus 403 ms
 * copying them, every millisecond of it on one {@code Conaco-Disk} thread.
 *
 * <p>Cheap to be wrong about. A cover that has not arrived yet is not an error — the container
 * falls back to reading it from the share exactly as it did before, so the worst a failed prefetch
 * costs is the time it would have cost anyway.
 */
public final class SmbCoverCache {

    private static final String TAG = "SmbCoverCache";
    private static final String CACHE_SUBDIR = "smb_cover";

    /**
     * Galleries already fetched or being fetched in this process. Covers do not change under us —
     * a re-save writes a new file and the inventory row is rebuilt — so once is enough, and a list
     * scrolled up and down would otherwise re-queue the same work on every pass.
     */
    private static final Set<Long> REQUESTED = Collections.synchronizedSet(new HashSet<>());

    private SmbCoverCache() {}

    private static File cacheDir() {
        File dir = new File(EhApplication.getInstance().getCacheDir(), CACHE_SUBDIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** Where a gallery's cover is staged. Deterministic, so a later read can just look. */
    @NonNull
    public static File cacheFileFor(long gid) {
        return new File(cacheDir(), Long.toString(gid));
    }

    /**
     * The staged cover, or null if it is not there yet.
     *
     * <p>Zero-length means a fetch that failed or a gallery with no cover at all; treating it as
     * absent lets the caller fall back rather than hand Conaco an empty file to decode.
     */
    @Nullable
    public static File staged(long gid) {
        File f = cacheFileFor(gid);
        return f.exists() && f.length() > 0 ? f : null;
    }

    /**
     * Starts fetching the covers for a page of inventory rows.
     *
     * <p>Called with the rows a page just produced rather than with the whole share: the point is
     * to have a cover ready by the time its row is drawn, and fetching four hundred of them to show
     * twelve would only make the first twelve slower.
     */
    public static void prefetch(@NonNull List<GalleryInfo> infos) {
        if (!SmbStorage.isConfigured()) {
            return;
        }
        for (GalleryInfo info : infos) {
            if (info == null || !REQUESTED.add(info.gid)) {
                continue;
            }
            final GalleryInfo lookup = SmbStorage.lookupKey(info.gid, info.title);
            SmbPreviewCache.prefetchExecutor().submit(() -> fetchOne(lookup));
        }
    }

    private static void fetchOne(@NonNull GalleryInfo info) {
        File target = cacheFileFor(info.gid);
        if (target.exists() && target.length() > 0) {
            return;
        }
        long t0 = SystemClock.elapsedRealtime();
        // Written to a sibling and renamed, so a reader can never open a half-copied cover. The
        // same reason every write on the share itself goes through a temporary name.
        File temp = new File(target.getParentFile(), target.getName() + ".part");
        com.hippo.streampipe.InputStreamPipe pipe = SmbStorage.openSmbCoverInputStreamPipe(info);
        if (pipe == null) {
            Log.i("SmbPerf", "cover.prefetch gid=" + info.gid + " none "
                    + (SystemClock.elapsedRealtime() - t0) + "ms");
            return;
        }
        InputStream is = null;
        OutputStream os = null;
        try {
            pipe.obtain();
            is = pipe.open();
            os = new FileOutputStream(temp);
            IOUtils.copy(is, os);
            IOUtils.closeQuietly(os);
            os = null;
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);
            Log.i("SmbPerf", "cover.prefetch gid=" + info.gid + " bytes=" + target.length()
                    + " " + (SystemClock.elapsedRealtime() - t0) + "ms thr="
                    + Thread.currentThread().getName());
        } catch (Throwable e) {
            Log.w(TAG, "Could not stage the cover for gid=" + info.gid, e);
            REQUESTED.remove(info.gid);
        } finally {
            IOUtils.closeQuietly(is);
            IOUtils.closeQuietly(os);
            pipe.close();
            pipe.release();
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    /**
     * Forgets a gallery's staged cover. Called when the gallery is deleted or re-synced, since
     * either can leave the staged copy showing something the share no longer holds.
     */
    public static void evict(long gid) {
        REQUESTED.remove(gid);
        //noinspection ResultOfMethodCallIgnored
        cacheFileFor(gid).delete();
    }
}
