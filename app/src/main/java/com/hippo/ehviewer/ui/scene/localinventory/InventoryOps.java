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
import com.hippo.ehviewer.smb.SmbPreviewCache;
import com.hippo.ehviewer.storage.GalleryTargets;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.SimpleHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

final class InventoryOps {

    /** All calls on the main thread. */
    interface Listener {
        void onRowResynced(@NonNull GalleryInfo fresh);

        /** {@code done} of {@code total} were updated. */
        void onResyncFinished(int done, int total);

        void onGalleryDeleted(@NonNull GalleryInfo gi);

        void onDeleteFinished(int gone, int total);
    }

    private final Executor executor;
    private final Listener listener;

    InventoryOps(@NonNull Executor executor, @NonNull Listener listener) {
        this.executor = executor;
        this.listener = listener;
    }

    /** Serial: a fan-out at e-hentai meets its rate limit. */
    void resyncMetadata(@NonNull Context appContext, @NonNull List<GalleryInfo> galleries) {
        final List<GalleryInfo> batch = new ArrayList<>(galleries);
        executor.execute(() -> {
            int updated = 0;
            for (GalleryInfo gi : batch) {
                final GalleryInfo fresh = NetworkStorage.active().metadata().resyncMetadata(appContext, gi);
                if (fresh != null) {
                    updated++;
                    // A re-sync can bring a different cover.
                    SmbCoverPrefetch.evict(gi.gid);
                    SimpleHandler.getInstance().post(() -> listener.onRowResynced(fresh));
                }
            }
            final int done = updated;
            SimpleHandler.getInstance().post(() -> listener.onResyncFinished(done, batch.size()));
        });
    }

    /** Pages already on the share are skipped, so only the holes download. */
    void repairMissingPages(@NonNull Context context, @NonNull List<GalleryInfo> galleries) {
        com.hippo.ehviewer.ui.NotificationPermission.onDownloadStart(context);
        for (GalleryInfo gi : galleries) {
            SmbDirectDownloader.getInstance().start(context, gi);
        }
    }

    /** A gallery being downloaded is cancelled instead: its download owns the folder. */
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
                if (NetworkStorage.active().lifecycle().deleteGalleryFolder(gi)) {
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

    private static void evictTraces(@NonNull Context appContext, @NonNull GalleryInfo gi) {
        GalleryTargets.unmark(gi.gid);
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
