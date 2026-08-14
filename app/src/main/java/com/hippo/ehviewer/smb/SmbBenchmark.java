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

import com.hippo.ehviewer.storage.GalleryRef;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Measures the share at the CONFIGURED settings (auto-tune is what searches). Read-only; needs
 * galleries to read.
 */
public final class SmbBenchmark {

    private static final String TAG = "SmbBenchmark";

    // Enough to overlap at the configured concurrency, few enough to be instant.
    private static final int METADATA_SAMPLE = 12;

    /**
     * Page images are megabytes, so the sample is small. This is enough to tell a share that
     * streams from one that stalls, which is the question being asked.
     */
    private static final int IMAGE_SAMPLE = 6;

    /** What the benchmark found. Plain values; the settings screen decides how to say them. */
    public static final class Result {
        public final boolean ok;
        /** Why there is nothing to report, when {@link #ok} is false. */
        @Nullable public final String problem;

        public final int galleriesOnShare;
        public final int metadataConcurrency;
        public final int imageConcurrency;

        /** One directory enumeration of {@code download/}. */
        public final long listMillis;

        public final int metadataRead;
        public final long metadataMillis;

        public final int imagesRead;
        public final long imageMillis;
        public final long imageBytes;

        Result(boolean ok, @Nullable String problem, int galleriesOnShare,
               int metadataConcurrency, int imageConcurrency, long listMillis,
               int metadataRead, long metadataMillis,
               int imagesRead, long imageMillis, long imageBytes) {
            this.ok = ok;
            this.problem = problem;
            this.galleriesOnShare = galleriesOnShare;
            this.metadataConcurrency = metadataConcurrency;
            this.imageConcurrency = imageConcurrency;
            this.listMillis = listMillis;
            this.metadataRead = metadataRead;
            this.metadataMillis = metadataMillis;
            this.imagesRead = imagesRead;
            this.imageMillis = imageMillis;
            this.imageBytes = imageBytes;
        }

        static Result unavailable(@NonNull String problem) {
            return new Result(false, problem, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        /** Milliseconds per gallery, the figure that predicts how a bigger share will feel. */
        public double millisPerGallery() {
            return metadataRead == 0 ? 0 : (double) metadataMillis / metadataRead;
        }

        public double imageMegabytesPerSecond() {
            return imageMillis == 0 ? 0 : (imageBytes / 1048576.0) / (imageMillis / 1000.0);
        }
    }

    private SmbBenchmark() {}

    /**
     * Runs the whole thing. Blocking and share-bound; call it from a worker thread.
     */
    @NonNull
    public static Result run() {
        if (!SmbConnection.isConfigured()) {
            return Result.unavailable("unconfigured");
        }
        int metaConcurrency = SmbConcurrency.metadata();
        int imageConcurrency = SmbConcurrency.image();

        long t0 = SystemClock.elapsedRealtime();
        List<GalleryRef> refs = SmbInventory.listGalleryRefs();
        long listMillis = SystemClock.elapsedRealtime() - t0;

        if (refs.isEmpty()) {
            return Result.unavailable("empty");
        }

        List<GalleryRef> sample =
                refs.subList(0, Math.min(METADATA_SAMPLE, refs.size()));

        // Metadata, through the same pool the inventory uses, so the number means something about
        // the app rather than about this class.
        ThreadPoolExecutor pool = SmbInventory.inventoryExecutor();
        long tMeta = SystemClock.elapsedRealtime();
        List<Future<Boolean>> pending = new ArrayList<>(sample.size());
        for (GalleryRef ref : sample) {
            pending.add(pool.submit(() -> SmbInventory.readGalleryInfo(ref) != null));
        }
        int metadataRead = 0;
        for (Future<Boolean> f : pending) {
            try {
                if (Boolean.TRUE.equals(f.get())) {
                    metadataRead++;
                }
            } catch (Throwable e) {
                Log.w(TAG, "A gallery's metadata could not be read while benchmarking", e);
            }
        }
        long metadataMillis = SystemClock.elapsedRealtime() - tMeta;

        Images images = readImages(refs, imageConcurrency);

        Result result = new Result(true, null, refs.size(), metaConcurrency, imageConcurrency,
                listMillis, metadataRead, metadataMillis,
                images.count, images.millis, images.bytes);
        Log.i("SmbPerf", "benchmark galleries=" + result.galleriesOnShare
                + " list=" + listMillis + "ms"
                + " meta=" + metadataRead + "/" + metaConcurrency + " " + metadataMillis + "ms"
                + " img=" + images.count + "/" + imageConcurrency + " " + images.millis + "ms "
                + images.bytes + "B");
        return result;
    }

    private static final class Images {
        final int count;
        final long millis;
        final long bytes;

        Images(int count, long millis, long bytes) {
            this.count = count;
            this.millis = millis;
            this.bytes = bytes;
        }
    }

    /** Reads real pages concurrently, spread across galleries so one book cannot skew it. */
    @NonNull
    private static Images readImages(@NonNull List<GalleryRef> refs, int concurrency) {
        List<Callable> jobs = new ArrayList<>();
        for (GalleryRef ref : refs) {
            if (jobs.size() >= IMAGE_SAMPLE) {
                break;
            }
            com.hippo.ehviewer.client.data.GalleryInfo info = SmbInventory.readGalleryInfo(ref);
            if (info == null) {
                continue;
            }
            jobs.add(new Callable(info));
        }
        if (jobs.isEmpty()) {
            return new Images(0, 0, 0);
        }

        ThreadPoolExecutor pool = SmbPreviewCache.prefetchExecutor();
        long t0 = SystemClock.elapsedRealtime();
        List<Future<Long>> pending = new ArrayList<>(jobs.size());
        for (Callable job : jobs) {
            pending.add(pool.submit(job::read));
        }
        long bytes = 0;
        int count = 0;
        for (Future<Long> f : pending) {
            try {
                long n = f.get();
                if (n > 0) {
                    bytes += n;
                    count++;
                }
            } catch (Throwable e) {
                Log.w(TAG, "A page could not be read while benchmarking", e);
            }
        }
        return new Images(count, SystemClock.elapsedRealtime() - t0, bytes);
    }

    /** Reads one gallery's first page and reports its size, or 0 if there is nothing to read. */
    private static final class Callable {
        private final com.hippo.ehviewer.client.data.GalleryInfo info;

        Callable(com.hippo.ehviewer.client.data.GalleryInfo info) {
            this.info = info;
        }

        long read() {
            try {
                com.hippo.streampipe.InputStreamPipe pipe =
                        SmbGalleryFiles.openSmbInputStreamPipe(info, 0);
                if (pipe == null) {
                    return 0L;
                }
                try {
                    pipe.obtain();
                    InputStream is = pipe.open();
                    byte[] buffer = new byte[SmbGalleryFiles.SMB_IO_BUFFER];
                    long total = 0;
                    int n;
                    while ((n = is.read(buffer)) > 0) {
                        total += n;
                    }
                    return total;
                } finally {
                    pipe.close();
                    pipe.release();
                }
            } catch (Throwable e) {
                Log.w(TAG, "Page read failed while benchmarking gid=" + info.gid, e);
                return 0L;
            }
        }
    }
}
