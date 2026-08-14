/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import jcifs.smb.SmbFile;

/**
 * Read-workflow coverage over every share the runner points it at — in practice the HDD and the
 * SSD NAS targets — through the exact production read path: folder enumeration, metadata,
 * covers, first pages. Each stage is timed and the numbers go to logcat as
 * {@code SmbPerf: workflow.read ...} lines for the runner to tabulate, so every future SMB
 * ticket gets a before/after on both disk classes for free.
 *
 * <p>Coverage asserts correctness — every sampled read must produce real bytes, and the library
 * must be the size the runner says it is (a run that quietly measures the wrong share dies here
 * instead of producing a plausible table). It deliberately asserts nothing about speed: those
 * numbers are noise-bound and belong in the report, not in a pass/fail.
 *
 * <p>Targets and credentials arrive as instrumentation arguments and never live in this
 * repository. Without {@code eh.targets} the whole class is skipped — CI and NAS-less checkouts
 * lose nothing. The device's own SMB configuration is snapshotted in {@code @Before} and
 * restored in {@code @After}, pass or fail.
 *
 * <p>The per-gid listing cache is cleared between targets by reflection — the HDD and SSD
 * libraries are copies of each other, so their gids collide, and a cached HDD listing answering
 * for the SSD would poison both the measurement and the correctness claim. Reflection rather
 * than a cache-clearing production method: the cache's API stays owned by production needs.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SmbReadWorkflowTest {

    private static final String TAG = "SmbPerf";

    private static final int METADATA_SAMPLE = 48;
    private static final int COVER_SAMPLE = 8;
    private static final int PAGE_SAMPLE = 4;

    private static final class Target {
        final String label;
        final String share;
        final String path;

        Target(String label, String share, String path) {
            this.label = label;
            this.share = share;
            this.path = path;
        }
    }

    private final List<Target> mTargets = new ArrayList<>();
    private int mMinGalleries;
    private String mUser;
    private String mPass;

    private String mOrigShare;
    private String mOrigPath;
    private String mOrigUser;
    private String mOrigPass;

    @Before
    public void setUp() {
        Bundle args = InstrumentationRegistry.getArguments();
        String spec = args.getString("eh.targets", "");
        // label:share:/path,label:share:/path — colon-limited so the path keeps its slashes.
        for (String one : spec.split(",")) {
            String[] parts = one.split(":", 3);
            if (parts.length == 3 && !parts[0].isEmpty()) {
                mTargets.add(new Target(parts[0], parts[1], parts[2]));
            }
        }
        Assume.assumeFalse("no eh.targets given; read-workflow coverage skipped",
                mTargets.isEmpty());
        mMinGalleries = Integer.parseInt(args.getString("eh.minGalleries", "1"));
        mUser = args.getString("eh.user", Settings.getSmbUsername());
        mPass = args.getString("eh.pass", Settings.getSmbPassword());

        mOrigShare = Settings.getSmbShareName();
        mOrigPath = Settings.getSmbSharePath();
        mOrigUser = Settings.getSmbUsername();
        mOrigPass = Settings.getSmbPassword();
    }

    @After
    public void restoreConfiguration() throws Exception {
        if (mTargets.isEmpty()) {
            return;     // skipped before the snapshot; nothing to restore
        }
        // commit(), not Settings.putString(): that goes through apply(), whose disk write is
        // asynchronous — and `am instrument` kills this process the moment the run ends, which
        // is exactly soon enough to drop it. The first version of this restore "worked" all
        // run long and left the device pointed at the HDD target anyway.
        boolean written = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(InstrumentationRegistry.getInstrumentation()
                        .getTargetContext())
                .edit()
                .putString(Settings.KEY_SMB_SHARE_NAME, mOrigShare)
                .putString(Settings.KEY_SMB_SHARE_PATH, mOrigPath)
                .putString(Settings.KEY_SMB_USERNAME, mOrigUser)
                .putString(Settings.KEY_SMB_PASSWORD, mOrigPass)
                .commit();
        assertTrue("restoring the device's SMB configuration failed", written);
        clearListingCache();
    }

    // --- the stages -------------------------------------------------------------------------

    @Test
    public void metadataReadsOffEveryTarget() throws Exception {
        forEachTarget(target -> {
            long t0 = SystemClock.elapsedRealtime();
            List<SmbStorage.GalleryRef> refs = SmbStorage.listGalleryRefs();
            long enumMs = SystemClock.elapsedRealtime() - t0;
            assertTrue(target.label + ": expected a library of at least " + mMinGalleries
                            + " galleries, found " + refs.size()
                            + " — is this the right share?",
                    refs.size() >= mMinGalleries);

            List<SmbStorage.GalleryRef> sample =
                    refs.subList(0, Math.min(METADATA_SAMPLE, refs.size()));
            long t1 = SystemClock.elapsedRealtime();
            int ok = 0;
            List<String> missing = new ArrayList<>();
            ExecutorService pool = SmbStorage.inventoryExecutor();
            List<Future<GalleryInfo>> pending = new ArrayList<>(sample.size());
            for (SmbStorage.GalleryRef ref : sample) {
                pending.add(pool.submit(() -> SmbStorage.readGalleryInfo(ref)));
            }
            for (int i = 0; i < pending.size(); i++) {
                if (pending.get(i).get() != null) {
                    ok++;
                } else {
                    missing.add(sample.get(i).folderName);
                }
            }
            long metaMs = SystemClock.elapsedRealtime() - t1;
            // A folder without readable metadata is the share's ordinary condition —
            // the inventory skips it, so does this. Both LG_Panda copies carry a
            // couple. What the floor catches is the read path itself failing, or the
            // whole sample quietly answering from the wrong place.
            assertTrue(target.label + ": only " + ok + "/" + sample.size()
                            + " metadata reads succeeded; missing: " + missing,
                    ok >= sample.size() * 9 / 10);
            Log.i(TAG, "workflow.read stage=metadata target=" + target.label
                    + " n=" + refs.size() + " enum_ms=" + enumMs
                    + " sample=" + sample.size() + " ok=" + ok
                    + (missing.isEmpty() ? "" : " missing=" + missing)
                    + " meta_ms=" + metaMs);
        });
    }

    @Test
    public void coverReadsOffEveryTarget() throws Exception {
        forEachTarget(target -> {
            List<GalleryInfo> infos = firstInfos(COVER_SAMPLE, target.label);
            long t0 = SystemClock.elapsedRealtime();
            long bytes = 0;
            for (GalleryInfo info : infos) {
                byte[] cover = SmbStorage.readCoverBytes(info);
                assertNotNull(target.label + ": no cover bytes for gid=" + info.gid, cover);
                assertTrue(target.label + ": empty cover for gid=" + info.gid, cover.length > 0);
                bytes += cover.length;
            }
            long ms = SystemClock.elapsedRealtime() - t0;
            Log.i(TAG, "workflow.read stage=cover target=" + target.label
                    + " sample=" + infos.size() + " bytes=" + bytes + " cover_ms=" + ms);
        });
    }

    @Test
    public void firstPagesReadOffEveryTarget() throws Exception {
        forEachTarget(target -> {
            List<GalleryInfo> infos = firstInfos(PAGE_SAMPLE, target.label);
            long t0 = SystemClock.elapsedRealtime();
            long bytes = 0;
            for (GalleryInfo info : infos) {
                SmbFile page = SmbStorage.findSmbImageFileForPreview(info, 0);
                assertNotNull(target.label + ": no first page for gid=" + info.gid, page);
                long got = drain(page);
                assertTrue(target.label + ": empty first page for gid=" + info.gid, got > 0);
                bytes += got;
            }
            long ms = SystemClock.elapsedRealtime() - t0;
            Log.i(TAG, "workflow.read stage=page target=" + target.label
                    + " sample=" + infos.size() + " bytes=" + bytes + " page_ms=" + ms);
        });
    }

    // --- plumbing ---------------------------------------------------------------------------

    private interface Stage {
        void run(Target target) throws Exception;
    }

    private void forEachTarget(Stage stage) throws Exception {
        for (Target target : mTargets) {
            Settings.putString(Settings.KEY_SMB_SHARE_NAME, target.share);
            Settings.putString(Settings.KEY_SMB_SHARE_PATH, target.path);
            Settings.putString(Settings.KEY_SMB_USERNAME, mUser);
            Settings.putString(Settings.KEY_SMB_PASSWORD, mPass);
            clearListingCache();
            stage.run(target);
        }
    }

    /** Metadata for the first {@code count} galleries of the currently configured target. */
    private List<GalleryInfo> firstInfos(int count, String label) {
        List<SmbStorage.GalleryRef> refs = SmbStorage.listGalleryRefs();
        assertTrue(label + ": expected a library of at least " + mMinGalleries
                + " galleries, found " + refs.size(), refs.size() >= mMinGalleries);
        List<GalleryInfo> infos = new ArrayList<>();
        for (SmbStorage.GalleryRef ref : refs) {
            if (infos.size() >= count) {
                break;
            }
            GalleryInfo info = SmbStorage.readGalleryInfo(ref);
            if (info != null) {
                infos.add(info);
            }
        }
        assertEquals(label + ": could not read metadata for a full sample",
                count, infos.size());
        return infos;
    }

    /** Streams a page off the share and throws the bytes away, counting them. */
    private static long drain(@NonNull SmbFile page) throws Exception {
        byte[] scratch = new byte[64 * 1024];
        long total = 0;
        try (InputStream in = new java.io.BufferedInputStream(
                page.getInputStream(), SmbStorage.SMB_IO_BUFFER)) {
            int n;
            while ((n = in.read(scratch)) > 0) {
                total += n;
            }
        }
        return total;
    }

    private static void clearListingCache() throws Exception {
        Field cacheField = SmbStorage.class.getDeclaredField("LISTING_CACHE");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        Field entries = cache.getClass().getDeclaredField("entries");
        entries.setAccessible(true);
        ((Map<?, ?>) entries.get(cache)).clear();
    }
}
