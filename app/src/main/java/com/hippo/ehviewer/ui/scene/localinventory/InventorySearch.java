/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.InventoryQuery;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.storage.SortMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** One search of the library: answered from what the phone has, then checked against the share. */
final class InventorySearch {

    @NonNull
    final InventoryQuery query;
    /** As typed, for the search bar's title. */
    @NonNull
    final String keyword;
    /** What the pages show; replaced, never mutated. */
    @Nullable
    volatile InventoryPager.Ordering shown;

    InventorySearch(@NonNull InventoryQuery query, @NonNull String keyword) {
        this.query = query;
        this.keyword = keyword;
    }

    /** Worker thread. From the phone's copy, else from the folder names the library page listed. */
    @Nullable
    InventoryPager.Ordering quickAnswer(@NonNull InventorySearchCache cache,
                                        @Nullable InventoryPager.Ordering listing, @NonNull SortMode mode) {
        List<InventorySearchCache.Entry> cached = cache.cached();
        if (!cached.isEmpty()) {
            return matching(cached, mode);
        }
        if (listing != null && query.answersFromTitles()) {
            return titleHits(listing);
        }
        return null;
    }

    /** Worker thread. Lists the share and brings the phone's copy up to date first. */
    @NonNull
    InventoryPager.Ordering checkedAnswer(@NonNull InventorySearchCache cache, @NonNull InventoryPager pager,
                                          @NonNull SortMode mode) throws Exception {
        List<GalleryRef> refs = pager.listBounded();
        List<InventorySearchCache.Entry> entries;
        try {
            entries = cache.refresh(refs, InventoryPager.stallMillis());
        } catch (TimeoutException e) {
            throw InventoryPager.shareTimeout();
        }
        return matching(entries, mode);
    }

    void renameRef(@NonNull String from, @NonNull String to) {
        InventoryPager.Ordering current = shown;
        if (current != null) {
            shown = current.renamed(from, to);
        }
    }

    void forgetRef(@NonNull String folderName) {
        InventoryPager.Ordering current = shown;
        if (current != null) {
            shown = current.without(folderName);
        }
    }

    /** Same galleries in the same order, from the same records where both hold them. */
    static boolean sameAnswer(@NonNull InventoryPager.Ordering a, @NonNull InventoryPager.Ordering b) {
        if (a.refs.size() != b.refs.size()) {
            return false;
        }
        for (int i = 0, n = a.refs.size(); i < n; i++) {
            String folder = a.refs.get(i).folderName;
            if (!folder.equals(b.refs.get(i).folderName)) {
                return false;
            }
            if (a.infos != null && b.infos != null && a.infos.get(folder) != b.infos.get(folder)) {
                return false;
            }
        }
        return true;
    }

    /** Sorted as the library page sorts: by the listing's folder time for the date sort. */
    @NonNull
    private InventoryPager.Ordering matching(@NonNull List<InventorySearchCache.Entry> entries,
                                             @NonNull SortMode mode) {
        List<SortMode.Entry> hits = new ArrayList<>();
        Map<SortMode.Entry, GalleryRef> refOf = new IdentityHashMap<>();
        for (InventorySearchCache.Entry entry : entries) {
            if (query.matches(entry.info)) {
                SortMode.Entry hit = new SortMode.Entry(entry.info, entry.ref.folderMtime);
                hits.add(hit);
                refOf.put(hit, entry.ref);
            }
        }
        Collections.sort(hits, mode.comparator());
        List<GalleryRef> refs = new ArrayList<>(hits.size());
        Map<String, GalleryInfo> infos = new HashMap<>();
        for (SortMode.Entry hit : hits) {
            GalleryRef ref = refOf.get(hit);
            refs.add(ref);
            infos.put(ref.folderName, hit.info);
        }
        return new InventoryPager.Ordering(refs, infos);
    }

    /** In the library page's order; its records too, when it read them all. */
    @NonNull
    private InventoryPager.Ordering titleHits(@NonNull InventoryPager.Ordering listing) {
        List<GalleryRef> refs = new ArrayList<>();
        for (GalleryRef ref : listing.refs) {
            if (query.matchesTitle(NetworkStorage.active().parseGalleryTitle(ref.folderName))) {
                refs.add(ref);
            }
        }
        return new InventoryPager.Ordering(refs, listing.infos);
    }
}
