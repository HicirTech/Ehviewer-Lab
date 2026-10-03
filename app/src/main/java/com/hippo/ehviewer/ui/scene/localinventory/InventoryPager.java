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

final class InventoryPager {

    /** Bounds the SMB metadata reads a page waits on. */
    static final int PAGE_SIZE = 50;

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

    /** infos is null when metadata is read lazily per page (the date sort). */
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

    /** jcifs timeouts need a global context rebuild, so the read runs on a throwaway thread. */
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
                    .getString(R.string.local_inventory_timeout, NetworkStorage.active().displayName()));
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
        SmbCoverPrefetch.prefetch(data);
        return new Page(data, pages);
    }

    @NonNull
    private Ordering buildOrdering(@NonNull SortMode mode) {
        if (mode == SortMode.DOWNLOAD_DATE_DESC) {
            List<GalleryRef> refs = NetworkStorage.active().inventory().listGalleryRefs();
            Collections.sort(refs, (a, b) -> Long.compare(b.folderMtime, a.folderMtime));
            return new Ordering(refs, null);
        }
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

    /** Replaced, not mutated: a page load may be reading the current ordering. */
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
