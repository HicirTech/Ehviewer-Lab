/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbCoverPrefetch;

import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.storage.SortMode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The inventory's paging data source (#99): one Ordering per refresh, sliced per page, kept in
 * step through deletes and renames. No Android UI in here.
 */
final class InventoryPager {

    /** Galleries read per page; bounds the SMB metadata reads before a page can render. */
    static final int PAGE_SIZE = 50;

    /** Hard cap per page load — an unreachable share must surface an error, not hang (#2644). */
    private static final long LOAD_TIMEOUT_S = 7;

    /** One page's galleries plus the total page count. */
    static final class Page {
        @NonNull final List<GalleryInfo> data;
        final int pages;

        Page(@NonNull List<GalleryInfo> data, int pages) {
            this.data = data;
            this.pages = pages;
        }
    }

    /**
     * The full display ordering, computed once per refresh. infos is null for the date sort
     * (metadata read lazily per page) and holds every record for sorts that had to read them all.
     */
    private static final class Ordering {
        @NonNull final List<GalleryRef> refs;
        @Nullable final Map<String, GalleryInfo> infos;

        Ordering(@NonNull List<GalleryRef> refs,
                 @Nullable Map<String, GalleryInfo> infos) {
            this.refs = refs;
            this.infos = infos;
        }
    }

    // volatile: assigned/read from the load executor.
    @Nullable
    private volatile Ordering mOrdering;

    /**
     * Loads one page under the timeout. jcifs socket timeouts cannot be bounded without
     * rebuilding the global context, so the read runs on a throwaway thread and is abandoned.
     */
    @NonNull
    Page loadPageBounded(@NonNull SortMode mode, int page, boolean rebuild) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "smb-inventory-page");
            t.setDaemon(true);
            return t;
        });
        Future<Page> fut = pool.submit(() -> loadPage(mode, page, rebuild));
        try {
            return fut.get(LOAD_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            fut.cancel(true);
            throw new IOException(EhApplication.getInstance()
                    .getString(R.string.local_inventory_timeout));
        } finally {
            pool.shutdownNow();
        }
    }

    @NonNull
    Page loadPage(@NonNull SortMode mode, int page, boolean rebuild) {
        Ordering ordering = mOrdering;
        if (rebuild || ordering == null) {
            ordering = buildOrdering(mode);
            mOrdering = ordering;
        }
        List<GalleryRef> refs = ordering.refs;
        int total = refs.size();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        List<GalleryInfo> data = new ArrayList<>();
        int from = page * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, total);
        for (int i = from; i < to; i++) {
            GalleryRef ref = refs.get(i);
            GalleryInfo gi = ordering.infos != null
                    ? ordering.infos.get(ref.folderName)
                    : NetworkStorage.active().inventory().readGalleryInfo(ref);
            if (gi != null) {
                data.add(gi);
            }
        }
        // Fan the page's covers out now; one that does not arrive in time is read the old way.
        SmbCoverPrefetch.prefetch(data);
        return new Page(data, pages);
    }

    @NonNull
    private Ordering buildOrdering(@NonNull SortMode mode) {
        if (mode == SortMode.DOWNLOAD_DATE_DESC) {
            // Ordered by the mtime the listing already carries; no metadata read until a page needs it.
            List<GalleryRef> refs = NetworkStorage.active().inventory().listGalleryRefs();
            Collections.sort(refs, (a, b) -> Long.compare(b.folderMtime, a.folderMtime));
            return new Ordering(refs, null);
        }
        // Other sorts need metadata fields, so the whole share is read and cached for the pages.
        List<GalleryInfo> loaded = NetworkStorage.active().inventory().loadInventory(mode);
        List<GalleryRef> refs = new ArrayList<>(loaded.size());
        Map<String, GalleryInfo> infos = new HashMap<>();
        for (GalleryInfo gi : loaded) {
            String folderName = NetworkStorage.active().galleryFolderName(gi);
            refs.add(new GalleryRef(folderName, 0L));
            infos.put(folderName, gi);
        }
        return new Ordering(refs, infos);
    }

    /** Re-points the ordering after a rename (#86); replaced, not mutated (concurrent readers). */
    void renameRef(@NonNull String from, @NonNull String to) {
        Ordering current = mOrdering;
        if (current == null) {
            return;
        }
        List<GalleryRef> refs = new ArrayList<>(current.refs.size());
        boolean found = false;
        for (GalleryRef ref : current.refs) {
            if (!found && ref.folderName.equals(from)) {
                found = true;
                refs.add(new GalleryRef(to, ref.folderMtime));
            } else {
                refs.add(ref);
            }
        }
        if (!found) {
            return;
        }
        Map<String, GalleryInfo> infos = null;
        if (current.infos != null) {
            infos = new HashMap<>(current.infos);
            GalleryInfo moved = infos.remove(from);
            if (moved != null) {
                infos.put(to, moved);
            }
        }
        mOrdering = new Ordering(refs, infos);
    }

    /** Drops a deleted folder, or its ref would come back as an unreadable row next page fetch. */
    void forgetRef(@NonNull String folderName) {
        Ordering current = mOrdering;
        if (current == null) {
            return;
        }
        List<GalleryRef> refs = new ArrayList<>(current.refs.size());
        boolean removed = false;
        for (GalleryRef ref : current.refs) {
            if (!removed && ref.folderName.equals(folderName)) {
                removed = true;
                continue;
            }
            refs.add(ref);
        }
        if (!removed) {
            return;
        }
        Map<String, GalleryInfo> infos = null;
        if (current.infos != null) {
            infos = new HashMap<>(current.infos);
            infos.remove(folderName);
        }
        mOrdering = new Ordering(refs, infos);
    }
}
