/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.streampipe.InputStreamPipe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fans inventory cover reads out ahead of Conaco's serial executor — into a bounded in-memory
 * buffer only; the share stays the sole durable copy (named disk caching was removed twice as an
 * architecture violation). Disk appears only as an anonymous decode shim (the decoder needs a
 * real fd). A missing buffer entry just falls back to the share.
 */
public final class SmbCoverPrefetch {

    private static final String TAG = "SmbCoverPrefetch";

    // Several pages of 2-140KB covers; LRU past this, evictees fall back to the share.
    private static final int MAX_BUFFERED_BYTES = 8 * 1024 * 1024;

    /** Access-ordered, so what {@link #evictOverflow} drops is the least recently shown. */
    private static final LinkedHashMap<Long, byte[]> BUFFER =
            new LinkedHashMap<>(32, 0.75f, true);
    private static int sBufferedBytes;

    // Dedup for this process; deliberately separate from BUFFER so LRU-dropped entries stay deduped.
    private static final Set<Long> REQUESTED = Collections.synchronizedSet(new HashSet<>());

    /**
     * Where the hl.8 build staged covers as named files. Swept once per process so an upgrade does
     * not leave the old cache sitting there looking trustworthy; plantable by tests.
     */
    private static volatile File sLegacyDir;
    private static boolean sLegacySwept;

    /** Where the decode shims are spilled; plantable by tests. */
    private static volatile File sShimDir;

    private SmbCoverPrefetch() {}

    /** Prefetches one page of rows (not the whole share — that would slow the visible twelve). */
    public static void prefetch(@NonNull List<GalleryInfo> infos) {
        if (!SmbConnection.isConfigured()) {
            return;
        }
        sweepLegacyOnce();
        for (GalleryInfo info : infos) {
            if (info == null || !REQUESTED.add(info.gid)) {
                continue;
            }
            final GalleryInfo lookup = SmbGalleryDirectory.lookupKey(info.gid, info.title);
            SmbPreviewCache.prefetchExecutor().submit(() -> {
                byte[] bytes = SmbGalleryFiles.readCoverBytes(lookup);
                if (bytes == null) {
                    // Nothing there, or the read failed; let a later scroll try again.
                    REQUESTED.remove(lookup.gid);
                    return;
                }
                put(lookup.gid, bytes);
            });
        }
    }

    /** Pipe over the buffered cover, or null (caller reads the share). Anonymous shim on open(). */
    @Nullable
    public static InputStreamPipe pipeFor(long gid) {
        final byte[] bytes;
        synchronized (BUFFER) {
            bytes = BUFFER.get(gid);
        }
        if (bytes == null) {
            return null;
        }
        return new InputStreamPipe() {
            private File shim;
            private FileInputStream fis;

            @Override public void obtain() {}

            @Override public void release() {}

            @Override
            public InputStream open() throws IOException {
                if (fis != null) {
                    throw new IllegalStateException("Please close it first");
                }
                shim = File.createTempFile("smb_cover_", null, shimDir());
                try (FileOutputStream os = new FileOutputStream(shim)) {
                    os.write(bytes);
                }
                fis = new FileInputStream(shim);
                return fis;
            }

            @Override
            public void close() {
                IOUtils.closeQuietly(fis);
                fis = null;
                if (shim != null) {
                    //noinspection ResultOfMethodCallIgnored
                    shim.delete();
                    shim = null;
                }
            }
        };
    }

    /**
     * Forgets a gallery's buffered cover. Called on delete and on re-sync — either can make the
     * buffered bytes disagree with the share, and the share wins.
     */
    public static void evict(long gid) {
        REQUESTED.remove(gid);
        synchronized (BUFFER) {
            byte[] removed = BUFFER.remove(gid);
            if (removed != null) {
                sBufferedBytes -= removed.length;
            }
        }
    }

    private static void put(long gid, @NonNull byte[] bytes) {
        synchronized (BUFFER) {
            byte[] previous = BUFFER.put(gid, bytes);
            if (previous != null) {
                sBufferedBytes -= previous.length;
            }
            sBufferedBytes += bytes.length;
            evictOverflow();
        }
    }

    private static void evictOverflow() {
        java.util.Iterator<Map.Entry<Long, byte[]>> it = BUFFER.entrySet().iterator();
        while (sBufferedBytes > MAX_BUFFERED_BYTES && it.hasNext()) {
            Map.Entry<Long, byte[]> eldest = it.next();
            sBufferedBytes -= eldest.getValue().length;
            it.remove();
        }
    }

    private static File shimDir() {
        File dir = sShimDir;
        if (dir == null) {
            dir = new File(EhApplication.getInstance().getCacheDir(), "smb_tmp");
            sShimDir = dir;
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Deletes the named-file cover cache the hl.8 build left behind. Once per process; the
     * directory itself goes too, so a device that upgraded does not carry a dead cache around.
     */
    private static synchronized void sweepLegacyOnce() {
        if (sLegacySwept) {
            return;
        }
        try {
            File dir = sLegacyDir;
            if (dir == null) {
                dir = new File(EhApplication.getInstance().getCacheDir(), "smb_cover");
                sLegacyDir = dir;
            }
            File[] leftovers = dir.listFiles();
            if (leftovers != null) {
                for (File f : leftovers) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        } catch (Throwable e) {
            Log.w(TAG, "Could not sweep the legacy cover cache", e);
        }
        sLegacySwept = true;
    }
}
