/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbCoverPrefetch;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.smb.SmbGalleryLifecycle;
import com.hippo.ehviewer.smb.SmbMetadata;
import com.hippo.ehviewer.smb.SmbPreviewCache;
import com.hippo.ehviewer.smb.SmbSpiderStorage;
import com.hippo.lib.yorozuya.SimpleHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The inventory's batch operations (#99): re-sync, repair, delete. Serial worker-thread flows;
 * results reach the screen through {@link Listener} on the main thread, and every SMB-side trace
 * of a deleted gallery is evicted here, not in the UI.
 */
final class InventoryOps {

    /** What the screen hears; all calls on the main thread. */
    interface Listener {
        /** A re-sync produced a fresh record for this row. */
        void onRowResynced(@NonNull GalleryInfo fresh);

        /** Re-sync finished: {@code done} of {@code total} updated. */
        void onResyncFinished(int done, int total);

        /** This gallery is gone from the share; drop its row. */
        void onGalleryDeleted(@NonNull GalleryInfo gi);

        /** Delete finished: {@code gone} of {@code total} deleted. */
        void onDeleteFinished(int gone, int total);
    }

    private final Executor executor;
    private final Listener listener;

    InventoryOps(@NonNull Executor executor, @NonNull Listener listener) {
        this.executor = executor;
        this.listener = listener;
    }

    /** Serial (a fan-out at e-hentai meets a rate limit); rows land one by one. */
    void resyncMetadata(@NonNull Context appContext, @NonNull List<GalleryInfo> galleries) {
        final List<GalleryInfo> batch = new ArrayList<>(galleries);
        executor.execute(() -> {
            int updated = 0;
            for (GalleryInfo gi : batch) {
                final GalleryInfo fresh = SmbMetadata.resyncMetadata(appContext, gi);
                if (fresh != null) {
                    updated++;
                    // A re-sync can bring a different cover; the buffered copy would keep the old one.
                    SmbCoverPrefetch.evict(gi.gid);
                    SimpleHandler.getInstance().post(() -> listener.onRowResynced(fresh));
                }
            }
            final int done = updated;
            SimpleHandler.getInstance().post(() -> listener.onResyncFinished(done, batch.size()));
        });
    }

    /** Re-enqueues; contain() skips what is already on the share, so only the holes download. */
    void repairMissingPages(@NonNull Context context, @NonNull List<GalleryInfo> galleries) {
        for (GalleryInfo gi : galleries) {
            SmbDirectDownloader.getInstance().start(context, gi);
        }
    }

    /**
     * Deletes serially; a folder that would not delete keeps its row. A gallery being downloaded
     * is cancelled instead — the download owns the folder, and cancel wipes it anyway.
     */
    void deleteGalleries(@NonNull Context appContext, @NonNull List<GalleryInfo> galleries) {
        final List<GalleryInfo> toErase = new ArrayList<>();
        for (GalleryInfo gi : new ArrayList<>(galleries)) {
            if (isBeingDownloaded(gi.gid)) {
                SmbDirectDownloader.getInstance().cancel(gi.gid);
                evictTraces(appContext, gi);
                listener.onGalleryDeleted(gi);
            } else {
                toErase.add(gi);
            }
        }
        if (toErase.isEmpty()) {
            return;
        }
        executor.execute(() -> {
            final List<GalleryInfo> gone = new ArrayList<>();
            for (GalleryInfo gi : toErase) {
                if (SmbGalleryLifecycle.deleteGalleryFolder(gi)) {
                    gone.add(gi);
                }
            }
            SimpleHandler.getInstance().post(() -> {
                for (GalleryInfo gi : gone) {
                    evictTraces(appContext, gi);
                    listener.onGalleryDeleted(gi);
                }
                listener.onDeleteFinished(gone.size(), toErase.size());
            });
        });
    }

    private static boolean isBeingDownloaded(long gid) {
        for (SmbDirectDownloader.TaskSnapshot t : SmbDirectDownloader.getInstance().snapshotTasks()) {
            if (t.gid == gid) {
                return true;
            }
        }
        return false;
    }

    /** Every local trace of a gallery that is no longer on the share. */
    private static void evictTraces(@NonNull Context appContext, @NonNull GalleryInfo gi) {
        SmbSpiderStorage.unmarkGidAsSmbTarget(gi.gid);
        SmbPreviewCache.evictGallery(gi.gid);
        SmbCoverPrefetch.evict(gi.gid);
        try {
            EhApplication.getConaco(appContext).getBeerBelly()
                    .remove(EhCacheKeyFactory.getThumbKey(gi.gid));
        } catch (Throwable ignored) {
            // A stale cover is cosmetic; never fail the delete over it.
        }
    }
}
