/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbCoverPrefetch;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.smb.SmbGalleryLifecycle;
import com.hippo.ehviewer.smb.SmbMetadata;
import com.hippo.ehviewer.smb.SmbPreviewCache;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The batch flows (#99): what a re-sync replaces, what a delete drops, what a failure keeps. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {InventoryOpsTest.ShadowSmbMetadata.class,
                   InventoryOpsTest.ShadowSmbGalleryLifecycle.class,
                   InventoryOpsTest.ShadowSmbDirectDownloader.class,
                   InventoryOpsTest.ShadowSmbPreviewCache.class,
                   InventoryOpsTest.ShadowSmbCoverPrefetch.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class InventoryOpsTest {

    static final List<Long> resyncable = new ArrayList<>();
    static final List<Long> deletable = new ArrayList<>();
    static final List<Long> downloading = new ArrayList<>();
    static final List<String> calls = new ArrayList<>();

    @Implements(SmbMetadata.class)
    public static class ShadowSmbMetadata {
        @Implementation
        protected static GalleryInfo resyncMetadata(Context context, GalleryInfo info) {
            if (!resyncable.contains(info.gid)) {
                return null;
            }
            GalleryInfo fresh = new GalleryInfo();
            fresh.gid = info.gid;
            fresh.title = info.title + "'";
            return fresh;
        }
    }

    @Implements(SmbGalleryLifecycle.class)
    public static class ShadowSmbGalleryLifecycle {
        @Implementation
        protected static boolean deleteGalleryFolder(GalleryInfo info) {
            calls.add("erase:" + info.gid);
            return deletable.contains(info.gid);
        }
    }

    @Implements(SmbDirectDownloader.class)
    public static class ShadowSmbDirectDownloader {
        // The real singleton may predate this shadow config (sandboxes are reused across test
        // classes), leaving it bound to a default shadow. A fresh instance binds to this one.
        static SmbDirectDownloader fresh;

        @Implementation
        protected static SmbDirectDownloader getInstance() {
            if (fresh == null) {
                try {
                    java.lang.reflect.Constructor<SmbDirectDownloader> c =
                            SmbDirectDownloader.class.getDeclaredConstructor();
                    c.setAccessible(true);
                    fresh = c.newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return fresh;
        }

        @Implementation
        protected List<SmbDirectDownloader.TaskSnapshot> snapshotTasks() {
            List<SmbDirectDownloader.TaskSnapshot> out = new ArrayList<>();
            for (long gid : downloading) {
                out.add(snapshot(gid));
            }
            return out;
        }

        // The ctor is package-private in smb; reflection, not a visibility change for a test.
        private static SmbDirectDownloader.TaskSnapshot snapshot(long gid) {
            try {
                java.lang.reflect.Constructor<SmbDirectDownloader.TaskSnapshot> c =
                        SmbDirectDownloader.TaskSnapshot.class.getDeclaredConstructor(
                                long.class, String.class, int.class, int.class,
                                SmbDirectDownloader.TaskSnapshot.State.class);
                c.setAccessible(true);
                return c.newInstance(gid, "t", 0, 0,
                        SmbDirectDownloader.TaskSnapshot.State.ACTIVE);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Implementation
        protected void cancel(long gid) {
            calls.add("cancel:" + gid);
        }

        @Implementation
        protected void start(Context context, GalleryInfo info) {
            calls.add("start:" + info.gid);
        }
    }

    @Implements(SmbPreviewCache.class)
    public static class ShadowSmbPreviewCache {
        @Implementation
        protected static void evictGallery(long gid) {}
    }

    @Implements(SmbCoverPrefetch.class)
    public static class ShadowSmbCoverPrefetch {
        @Implementation
        protected static void evict(long gid) {
            calls.add("evictCover:" + gid);
        }
    }

    /** Records everything the screen would hear. */
    static final class Heard implements InventoryOps.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onRowResynced(@NonNull GalleryInfo fresh) {
            events.add("resynced:" + fresh.gid + ":" + fresh.title);
        }

        @Override
        public void onResyncFinished(int done, int total) {
            events.add("resyncDone:" + done + "/" + total);
        }

        @Override
        public void onGalleryDeleted(@NonNull GalleryInfo gi) {
            events.add("deleted:" + gi.gid);
        }

        @Override
        public void onDeleteFinished(int gone, int total) {
            events.add("deleteDone:" + gone + "/" + total);
        }
    }

    private Heard heard;
    private InventoryOps ops;

    @Before
    public void setUp() {
        resyncable.clear();
        deletable.clear();
        downloading.clear();
        calls.clear();
        ShadowSmbDirectDownloader.fresh = null;
        heard = new Heard();
        ops = new InventoryOps(Runnable::run, heard);
    }

    private static GalleryInfo gallery(long gid) {
        GalleryInfo gi = new GalleryInfo();
        gi.gid = gid;
        gi.title = "G" + gid;
        return gi;
    }

    /** A failed fetch replaces nothing; the count says so. */
    @Test
    public void resyncReplacesOnlyWhatCameBack() {
        resyncable.add(1L);
        ops.resyncMetadata(RuntimeEnvironment.getApplication(),
                Arrays.asList(gallery(1), gallery(2)));
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("resynced:1:G1'", "resyncDone:1/2"), heard.events);
        assertEquals("a fresh cover may differ; the buffered one must go",
                Arrays.asList("evictCover:1"), calls);
    }

    /** A gallery mid-download is cancelled (cancel wipes the folder), never erased underneath. */
    @Test
    public void deletingADownloadingGalleryCancelsInstead() {
        downloading.add(1L);
        ops.deleteGalleries(RuntimeEnvironment.getApplication(),
                Arrays.asList(gallery(1)));
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("deleted:1"), heard.events);
        assertEquals(Arrays.asList("cancel:1", "evictCover:1"), calls);
    }

    /** A folder that would not delete keeps its row — no deleted event for it. */
    @Test
    public void aFailedDeleteKeepsItsRow() {
        deletable.add(1L);   // 2 refuses
        ops.deleteGalleries(RuntimeEnvironment.getApplication(),
                Arrays.asList(gallery(1), gallery(2)));
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("deleted:1", "deleteDone:1/2"), heard.events);
    }

    /** Repair is an enqueue per gallery, nothing more. */
    @Test
    public void repairEnqueuesEachGallery() {
        ops.repairMissingPages(RuntimeEnvironment.getApplication(),
                Arrays.asList(gallery(1), gallery(2)));
        assertEquals(Arrays.asList("start:1", "start:2"), calls);
    }
}
