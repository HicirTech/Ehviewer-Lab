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
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.streampipe.InputStreamPipe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prefetches past Conaco's serial loader into memory only; the share stays the sole copy. */
public final class SmbCoverPrefetch {

    private static final String TAG = "SmbCoverPrefetch";

    // Several pages of 2-140KB covers.
    private static final int MAX_BUFFERED_BYTES = 8 * 1024 * 1024;

    /** Access-ordered, so what {@link #evictOverflow} drops is the least recently shown. */
    private static final LinkedHashMap<Long, byte[]> BUFFER =
            new LinkedHashMap<>(32, 0.75f, true);
    private static int sBufferedBytes;

    // Separate from BUFFER so an evicted cover is not prefetched again.
    private static final Set<Long> REQUESTED = Collections.synchronizedSet(
            Collections.newSetFromMap(new java.util.LinkedHashMap<Long, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                    return size() > 512;
                }
            }));

    /** The hl.8 build's named-file cover cache; tests plant their own. */
    private static volatile File sLegacyDir;
    private static boolean sLegacySwept;

    private SmbCoverPrefetch() {}

    /** One page of rows, not the whole share: that would slow the visible ones. */
    public static void prefetch(@NonNull List<GalleryInfo> infos) {
        if (!NetworkStorage.active().isConfigured()) {
            return;
        }
        sweepLegacyOnce();
        for (GalleryInfo info : infos) {
            if (info == null || !REQUESTED.add(info.gid)) {
                continue;
            }
            final GalleryInfo lookup = NetworkStorage.lookupKey(info.gid, info.title);
            SmbPreviewCache.prefetchExecutor().submit(() -> {
                byte[] bytes = NetworkStorage.active().files().readCoverBytes(lookup);
                if (bytes == null) {
                    REQUESTED.remove(lookup.gid);
                    return;
                }
                put(lookup.gid, bytes);
            });
        }
    }

    /** open() stages an anonymous temp file: the decoder needs a real fd. */
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
        return SmbShims.dir();
    }

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
