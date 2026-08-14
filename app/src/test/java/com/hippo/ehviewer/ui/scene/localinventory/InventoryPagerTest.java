/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbCoverPrefetch;
import com.hippo.ehviewer.smb.SmbInventory;
import com.hippo.ehviewer.smb.SmbSortMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.List;

/** The paging data source (#99): slicing, lazy-vs-cached ordering, delete/rename maintenance. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {InventoryPagerTest.ShadowSmbInventory.class,
                   InventoryPagerTest.ShadowSmbCoverPrefetch.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class InventoryPagerTest {

    static final List<SmbInventory.GalleryRef> refsOnShare = new ArrayList<>();
    static final List<GalleryInfo> inventoryOnShare = new ArrayList<>();
    static int listCalls;
    static int readCalls;

    @Implements(SmbInventory.class)
    public static class ShadowSmbInventory {
        @Implementation
        protected static List<SmbInventory.GalleryRef> listGalleryRefs() {
            listCalls++;
            return new ArrayList<>(refsOnShare);
        }

        @Implementation
        protected static GalleryInfo readGalleryInfo(SmbInventory.GalleryRef ref) {
            readCalls++;
            GalleryInfo gi = new GalleryInfo();
            gi.gid = Long.parseLong(ref.folderName.split("-")[0]);
            gi.title = ref.folderName;
            return gi;
        }

        @Implementation
        protected static List<GalleryInfo> loadInventory(SmbSortMode mode) {
            return new ArrayList<>(inventoryOnShare);
        }
    }

    @Implements(SmbCoverPrefetch.class)
    public static class ShadowSmbCoverPrefetch {
        @Implementation
        protected static void prefetch(List<GalleryInfo> infos) {}
    }

    private InventoryPager pager;

    @Before
    public void setUp() {
        refsOnShare.clear();
        inventoryOnShare.clear();
        listCalls = 0;
        readCalls = 0;
        pager = new InventoryPager();
    }

    private static void seedRefs(int n) {
        for (int i = 0; i < n; i++) {
            refsOnShare.add(new SmbInventory.GalleryRef((i + 1) + "-G" + (i + 1), 1000L + i));
        }
    }

    /** 120 refs slice into 3 pages of 50/50/20, and only the page's metadata is read. */
    @Test
    public void pagesSliceTheOrderingAndReadLazily() {
        seedRefs(120);
        InventoryPager.Page p0 = pager.loadPage(SmbSortMode.DOWNLOAD_DATE_DESC, 0, true);
        assertEquals(3, p0.pages);
        assertEquals(50, p0.data.size());
        assertEquals(50, readCalls);

        InventoryPager.Page p2 = pager.loadPage(SmbSortMode.DOWNLOAD_DATE_DESC, 2, false);
        assertEquals(20, p2.data.size());
        assertEquals(70, readCalls);
        assertEquals("paging must not re-list the share", 1, listCalls);
    }

    /** Date sort orders by folder mtime, newest first, straight off the listing. */
    @Test
    public void dateSortOrdersByMtimeDescending() {
        seedRefs(3);   // mtimes 1000, 1001, 1002
        InventoryPager.Page page = pager.loadPage(SmbSortMode.DOWNLOAD_DATE_DESC, 0, true);
        assertEquals(3L, page.data.get(0).gid);
        assertEquals(1L, page.data.get(2).gid);
    }

    /** Non-date sorts read the whole share once and serve pages from the cached records. */
    @Test
    public void metadataSortsServePagesFromTheCachedRecords() {
        for (int i = 0; i < 3; i++) {
            GalleryInfo gi = new GalleryInfo();
            gi.gid = i + 1;
            gi.title = "T" + (i + 1);
            inventoryOnShare.add(gi);
        }
        InventoryPager.Page page = pager.loadPage(SmbSortMode.TITLE_ASC, 0, true);
        assertEquals(3, page.data.size());
        assertEquals("cached ordering must not read per row", 0, readCalls);
    }

    /** A deleted folder's ref is forgotten, or it would come back as an unreadable row. */
    @Test
    public void forgottenRefsLeaveTheOrdering() {
        seedRefs(2);
        pager.loadPage(SmbSortMode.DOWNLOAD_DATE_DESC, 0, true);
        pager.forgetRef("1-G1");
        InventoryPager.Page page = pager.loadPage(SmbSortMode.DOWNLOAD_DATE_DESC, 0, false);
        assertEquals(1, page.data.size());
        assertEquals(2L, page.data.get(0).gid);
    }

    /** A renamed folder's ref is re-pointed, cached record riding along (#86). */
    @Test
    public void renamedRefsFollowTheirGallery() {
        GalleryInfo gi = new GalleryInfo();
        gi.gid = 1;
        gi.title = "Old";
        inventoryOnShare.add(gi);
        pager.loadPage(SmbSortMode.TITLE_ASC, 0, true);

        pager.renameRef("1-Old", "1-New");
        InventoryPager.Page page = pager.loadPage(SmbSortMode.TITLE_ASC, 0, false);
        assertEquals(1, page.data.size());
        assertEquals("the cached record must survive the rename", "Old", page.data.get(0).title);
        assertEquals(0, readCalls);
    }
}
