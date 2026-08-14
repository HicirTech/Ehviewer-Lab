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
 * Pulls the Local Inventory's covers off the share ahead of the list, several at a time — into
 * memory, and only into memory.
 *
 * <p>Conaco loads images one after another on a serial executor, so a screen of covers on a share
 * is a queue rather than a fan-out; this fans the fetch out the way {@link SmbPreviewCache} does
 * for the preview grid. What it deliberately does not do is keep anything: <b>the share is the only
 * durable copy of a cover anywhere.</b> Bytes fetched here live in a bounded in-process buffer and
 * die with the process — the same standing Conaco's own memory cache has. An earlier version
 * staged covers as named files on disk and served them across restarts; in a multi-device app that
 * is a device trusting its own past over a share other devices write to, and it was removed as an
 * architecture violation, twice — first the trust across restarts, then the disk residence
 * entirely.
 *
 * <p>One disk write survives, and it is not ours to remove: the upstream decode helper casts its
 * stream to {@code FileInputStream} ({@code ImageBitmapHelper.decode}), so bytes must sit behind a
 * real file descriptor for the instant of decoding. {@link #pipeFor} therefore spills the buffered
 * bytes to an anonymous temp file when the decoder asks and deletes it as the decode ends — the
 * same lifecycle the direct-from-share path has always used. A decode shim, not a record: nothing
 * on disk has a reusable name, and nothing outlives the one decode it served.
 *
 * <p>A cover that is not in the buffer is not an error. The container falls back to reading the
 * share directly, exactly as it did before any of this existed, so the worst a missed or evicted
 * prefetch costs is the time it would have cost anyway.
 */
public final class SmbCoverPrefetch {

    private static final String TAG = "SmbCoverPrefetch";

    /**
     * The most cover bytes held at once. Covers run 2–140 KB and a page of inventory is twelve of
     * them, so this holds several pages; past it the least recently touched are dropped and simply
     * fall back to the share.
     */
    private static final int MAX_BUFFERED_BYTES = 8 * 1024 * 1024;

    /** Access-ordered, so what {@link #evictOverflow} drops is the least recently shown. */
    private static final LinkedHashMap<Long, byte[]> BUFFER =
            new LinkedHashMap<>(32, 0.75f, true);
    private static int sBufferedBytes;

    /**
     * Galleries fetched or being fetched this process, so a list scrolled up and down does not
     * queue the same work again. Deliberately separate from {@link #BUFFER}: an entry the LRU
     * dropped stays here, and its rows just read the share directly.
     */
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

    /**
     * Starts fetching the covers for a page of inventory rows.
     *
     * <p>Called with the rows a page just produced rather than the whole share: the point is a
     * cover ready by the time its row draws, and fetching four hundred to show twelve would only
     * make the first twelve slower.
     */
    public static void prefetch(@NonNull List<GalleryInfo> infos) {
        if (!SmbStorage.isConfigured()) {
            return;
        }
        sweepLegacyOnce();
        for (GalleryInfo info : infos) {
            if (info == null || !REQUESTED.add(info.gid)) {
                continue;
            }
            final GalleryInfo lookup = SmbStorage.lookupKey(info.gid, info.title);
            SmbPreviewCache.prefetchExecutor().submit(() -> {
                byte[] bytes = SmbStorage.readCoverBytes(lookup);
                if (bytes == null) {
                    // Nothing there, or the read failed; let a later scroll try again.
                    REQUESTED.remove(lookup.gid);
                    return;
                }
                put(lookup.gid, bytes);
            });
        }
    }

    /**
     * A pipe over the buffered cover, or null when the buffer has nothing — in which case the
     * caller reads the share, which is the ordinary path and not a failure.
     *
     * <p>The pipe spills to an anonymous temp file on {@code open()} and deletes it on
     * {@code close()}, because the decode helper insists on a {@code FileInputStream}. The file
     * has no name any other code could find and no life beyond the decode.
     */
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
