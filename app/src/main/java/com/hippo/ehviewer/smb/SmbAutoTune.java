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
 * Finds the fastest concurrency for this share by asking the share, not a table.
 *
 * <p>Every number this app ever carried for "how many at once" turned out to be a property of one
 * particular library on one particular link: six looked optimal on twelve galleries because twelve
 * items cannot keep more than a few workers busy, and the same share kept scaling almost linearly
 * to sixteen and beyond once there were a hundred and forty. So instead of shipping a guess, the
 * settings screen can sweep the range and keep what actually won.
 *
 * <p><b>Everything measured here is a real share read; nothing local can answer.</b> Metadata goes
 * through {@link SmbStorage#readGalleryInfo}, which opens {@code metadata.json} on the share every
 * time — the app deliberately has no local metadata cache. Page bytes are streamed straight off
 * the {@link SmbFile} and discarded as they arrive: no temp file, no buffer kept, nothing for a
 * second pass to accidentally hit. A tuner that could be satisfied from a cache would tune the
 * cache.
 *
 * <p>Memory stays flat by construction. The image pass holds one 64 KB scratch buffer per worker
 * — at the widest setting that is 4 MB, released as the sweep ends — and page bytes are counted,
 * never accumulated. The sweep runs on its own pool so it neither resizes nor competes with the
 * pools the app is using for real work.
 *
 * <p>The sweep covers 1–128 by sampling {@link #CANDIDATES} rather than walking all sixty-four
 * values: between neighbours the curve cannot turn around, so the intermediate points only add
 * run time and noise. A level higher than the number of work items cannot be distinguished from
 * one equal to it, so candidates above the sample size are skipped rather than reported as ties.
 */
public final class SmbAutoTune {

    private static final String TAG = "SmbAutoTune";

    /** The sampled ladder over 1–128. */
    static final int[] CANDIDATES = {1, 2, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128};

    /**
     * How many metadata files one level reads. Small files, so even this many is a few hundred
     * kilobytes per level. It must sit comfortably above the top candidate, or the top levels
     * collapse into each other: at a sample equal to the level, every item runs at once and the
     * level above it measures the same thing.
     */
    private static final int METADATA_SAMPLE = 192;

    /**
     * Page images too must sample past the top candidate, or the image sweep is censored the way
     * the metadata sweep once was — its first run reported "16 is fastest" purely because sixteen
     * pages were all it had. The cost is honest to name: pages run hundreds of kilobytes, so a
     * full sweep moves a few hundred megabytes through the link. It moves them through a 64 KB
     * scratch per worker and drops them; nothing accumulates, whatever the level.
     */
    private static final int IMAGE_SAMPLE = 128;

    /**
     * How close to the winner a lower concurrency has to be to take the crown anyway. Run-to-run
     * noise is larger than a few percent, and when two levels are this close the one holding
     * fewer sockets open against the NAS is the better citizen.
     */
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

    /**
     * The lowest time wins; a lower concurrency within {@link #TIE_MARGIN} of it wins instead.
     *
     * <p>Pure and package-visible so the tie-break — the part with room to be subtly wrong — is
     * pinned by tests without a share in the room.
     */
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
