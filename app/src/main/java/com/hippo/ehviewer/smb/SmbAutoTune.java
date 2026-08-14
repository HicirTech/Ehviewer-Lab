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

import com.hippo.ehviewer.client.data.GalleryInfo;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jcifs.smb.SmbFile;

/**
 * Sweeps 1-128 and keeps what actually won — the optimum is a property of the library and link,
 * not a constant. Every measurement is a real share read (a tuner satisfiable from a cache would
 * tune the cache); page bytes are streamed and dropped, memory stays flat; candidates above the
 * sample size are skipped (indistinguishable = censored).
 */
public final class SmbAutoTune {

    private static final String TAG = "SmbAutoTune";

    /** The sampled ladder over 1–128. */
    static final int[] CANDIDATES = {1, 2, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128};

    // Must sit above the top candidate or the top levels measure the same thing.
    private static final int METADATA_SAMPLE = 192;

    // Same censoring rule ("16 is fastest" once meant "only had 16 pages"); a full sweep moves
    // a few hundred MB, through 64KB scratch buffers, accumulating nothing.
    private static final int IMAGE_SAMPLE = 128;

    // Within this of the winner, the lower level wins — noise exceeds a few percent anyway.
    private static final double TIE_MARGIN = 0.08;

    /** One stage of the sweep, for the settings screen to narrate. */
    public interface Progress {
        void on(@NonNull String stage, int concurrency);
    }

    public static final class Result {
        public final boolean ok;
        @Nullable public final String problem;
        public final int galleries;
        public final int imagesSampled;
        public final int bestMetadata;
        public final int bestImage;
        /** Level → milliseconds, in sweep order, for the result dialog. */
        public final Map<Integer, Long> metadataMillis;
        public final Map<Integer, Long> imageMillis;

        Result(boolean ok, @Nullable String problem, int galleries, int imagesSampled,
               int bestMetadata, int bestImage,
               Map<Integer, Long> metadataMillis, Map<Integer, Long> imageMillis) {
            this.ok = ok;
            this.problem = problem;
            this.galleries = galleries;
            this.imagesSampled = imagesSampled;
            this.bestMetadata = bestMetadata;
            this.bestImage = bestImage;
            this.metadataMillis = metadataMillis;
            this.imageMillis = imageMillis;
        }

        static Result unavailable(@NonNull String problem) {
            return new Result(false, problem, 0, 0, 0, 0,
                    new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    private SmbAutoTune() {}

    /** Blocking and share-bound; call from a worker thread. */
    @NonNull
    public static Result run(@Nullable Progress progress) {
        if (!SmbConnection.isConfigured()) {
            return Result.unavailable("unconfigured");
        }
        List<SmbInventory.GalleryRef> refs = SmbInventory.listGalleryRefs();
        if (refs.isEmpty()) {
            return Result.unavailable("empty");
        }

        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 5L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), r -> {
                    Thread t = new Thread(r, "smb-autotune");
                    t.setDaemon(true);
                    return t;
                });
        try {
            List<SmbInventory.GalleryRef> metaSample =
                    refs.subList(0, Math.min(METADATA_SAMPLE, refs.size()));

            // Warm-up, outside every timing: the first operations on a fresh process also pay for
            // the SMB session, and that cost belongs to nobody's concurrency level.
            List<GalleryInfo> warm = new ArrayList<>();
            for (int i = 0; i < Math.min(4, metaSample.size()); i++) {
                GalleryInfo gi = SmbInventory.readGalleryInfo(metaSample.get(i));
                if (gi != null) {
                    warm.add(gi);
                }
            }

            Map<Integer, Long> metaTimes = new LinkedHashMap<>();
            for (int conc : CANDIDATES) {
                if (conc > metaSample.size() && conc != 1) {
                    continue;   // indistinguishable from conc == sample size
                }
                if (progress != null) {
                    progress.on("metadata", conc);
                }
                SmbConcurrency.resize(pool, conc);
                long t0 = SystemClock.elapsedRealtime();
                List<Future<?>> pending = new ArrayList<>(metaSample.size());
                for (SmbInventory.GalleryRef ref : metaSample) {
                    pending.add(pool.submit(() -> SmbInventory.readGalleryInfo(ref)));
                }
                for (Future<?> f : pending) {
                    try {
                        f.get();
                    } catch (Throwable ignored) {
                        // A single unreadable gallery is the share's ordinary condition; the
                        // level is judged on the batch, not failed by one member.
                    }
                }
                metaTimes.put(conc, SystemClock.elapsedRealtime() - t0);
            }

            // Pages for the image pass: first page of each of the first galleries that have one.
            // Collected via the metadata read above where possible. Collection is itself a pile
            // of share round-trips, so it reports progress — on a large library it takes longer
            // than some measurement levels do.
            if (progress != null) {
                progress.on("collect", 0);
            }
            List<SmbFile> images = new ArrayList<>();
            for (SmbInventory.GalleryRef ref : refs) {
                if (images.size() >= IMAGE_SAMPLE) {
                    break;
                }
                GalleryInfo info = null;
                for (GalleryInfo w : warm) {
                    // Cheap reuse where the warm-up already parsed this folder.
                    if (ref.folderName.startsWith(w.gid + "-")) {
                        info = w;
                        break;
                    }
                }
                if (info == null) {
                    info = SmbInventory.readGalleryInfo(ref);
                }
                if (info == null) {
                    continue;
                }
                SmbFile page = SmbGalleryFiles.findSmbImageFileForPreview(
                        SmbGalleryDirectory.lookupKey(info.gid, info.title), 0);
                if (page != null) {
                    images.add(page);
                }
            }

            Map<Integer, Long> imageTimes = new LinkedHashMap<>();
            if (!images.isEmpty()) {
                for (int conc : CANDIDATES) {
                    if (conc > images.size() && conc != 1) {
                        continue;
                    }
                    if (progress != null) {
                        progress.on("image", conc);
                    }
                    SmbConcurrency.resize(pool, conc);
                    long t0 = SystemClock.elapsedRealtime();
                    List<Future<?>> pending = new ArrayList<>(images.size());
                    for (SmbFile page : images) {
                        pending.add(pool.submit(() -> drain(page)));
                    }
                    for (Future<?> f : pending) {
                        try {
                            f.get();
                        } catch (Throwable ignored) {
                        }
                    }
                    imageTimes.put(conc, SystemClock.elapsedRealtime() - t0);
                }
            }

            int bestMeta = pickBest(metaTimes);
            int bestImage = imageTimes.isEmpty() ? SmbConcurrency.image() : pickBest(imageTimes);
            Log.i("SmbPerf", "autotune galleries=" + refs.size()
                    + " meta=" + metaTimes + " -> " + bestMeta
                    + " image=" + imageTimes + " -> " + bestImage);
            return new Result(true, null, refs.size(), images.size(),
                    bestMeta, bestImage, metaTimes, imageTimes);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Lowest time wins; a lower level within TIE_MARGIN wins instead. Pure, pinned by tests. */
    static int pickBest(@NonNull Map<Integer, Long> times) {
        long best = Long.MAX_VALUE;
        for (long t : times.values()) {
            best = Math.min(best, t);
        }
        long acceptable = (long) Math.ceil(best * (1 + TIE_MARGIN));
        int winner = SmbConcurrency.DEFAULT_METADATA;
        long winnerTime = Long.MAX_VALUE;
        // Iteration order is sweep order — ascending concurrency — so the first level inside the
        // margin is the smallest one.
        for (Map.Entry<Integer, Long> e : times.entrySet()) {
            if (e.getValue() <= acceptable) {
                winner = e.getKey();
                winnerTime = e.getValue();
                break;
            }
        }
        if (winnerTime == Long.MAX_VALUE && !times.isEmpty()) {
            winner = times.keySet().iterator().next();
        }
        return SmbConcurrency.clamp(winner, SmbConcurrency.DEFAULT_METADATA);
    }

    /** Streams a page off the share and throws the bytes away, counting them. */
    private static long drain(@NonNull SmbFile page) {
        byte[] scratch = new byte[64 * 1024];
        long total = 0;
        try (InputStream in = new java.io.BufferedInputStream(
                page.getInputStream(), SmbGalleryFiles.SMB_IO_BUFFER)) {
            int n;
            while ((n = in.read(scratch)) > 0) {
                total += n;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Page stream failed during tuning: " + page.getName(), e);
        }
        return total;
    }
}
