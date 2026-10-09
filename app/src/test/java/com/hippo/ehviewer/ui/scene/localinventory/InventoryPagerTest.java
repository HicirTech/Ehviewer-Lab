/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.SortMode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbCoverPrefetch;
import com.hippo.ehviewer.smb.SmbInventory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The paging data source (#99): slicing, lazy-vs-cached ordering, delete/rename maintenance. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {InventoryPagerTest.ShadowSmbInventory.class,
                   InventoryPagerTest.ShadowSmbCoverPrefetch.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class InventoryPagerTest {

    static final List<GalleryRef> refsOnShare = new ArrayList<>();
    static final List<GalleryInfo> inventoryOnShare = new ArrayList<>();
    static int listCalls;
    static int readCalls;
    static IOException listFailure;

    @Implements(SmbInventory.class)
    public static class ShadowSmbInventory {
        @Implementation
        protected static List<GalleryRef> listGalleryRefs() throws IOException {
            listCalls++;
            if (listFailure != null) {
                throw listFailure;
            }
            return new ArrayList<>(refsOnShare);
        }

        @Implementation
        protected static List<GalleryInfo> readGalleryInfos(List<GalleryRef> refs, long stallMillis) {
            List<GalleryInfo> infos = new ArrayList<>();
            for (GalleryRef ref : refs) {
                readCalls++;
                GalleryInfo gi = new GalleryInfo();
                gi.gid = Long.parseLong(ref.folderName.split("-")[0]);
                gi.title = ref.folderName;
                infos.add(gi);
            }
            return infos;
        }

        @Implementation
        protected static List<GalleryInfo> loadInventory(SortMode mode) {
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
        GetText.initialize(RuntimeEnvironment.getApplication());
        refsOnShare.clear();
        inventoryOnShare.clear();
        listCalls = 0;
        readCalls = 0;
        listFailure = null;
        pager = new InventoryPager();
    }

    private static void seedRefs(int n) {
        for (int i = 0; i < n; i++) {
            refsOnShare.add(new GalleryRef((i + 1) + "-G" + (i + 1), 1000L + i));
        }
    }

    @Test
    public void pagesSliceTheOrderingAndReadLazily() throws Exception {
        seedRefs(120);
        InventoryPager.Page p0 = pager.loadPageBounded(SortMode.DOWNLOAD_DATE_DESC, 0, true);
        assertEquals(3, p0.pages);
        assertEquals(50, p0.data.size());
        assertEquals(50, readCalls);

        InventoryPager.Page p2 = pager.loadPageBounded(SortMode.DOWNLOAD_DATE_DESC, 2, false);
        assertEquals(20, p2.data.size());
        assertEquals(70, readCalls);
        assertEquals("paging must not re-list the share", 1, listCalls);
    }

    @Test
    public void dateSortOrdersByMtimeDescending() throws Exception {
        seedRefs(3);   // mtimes 1000, 1001, 1002
        InventoryPager.Page page = pager.loadPageBounded(SortMode.DOWNLOAD_DATE_DESC, 0, true);
        assertEquals(3L, page.data.get(0).gid);
        assertEquals(1L, page.data.get(2).gid);
    }

    @Test
    public void metadataSortsServePagesFromTheCachedRecords() throws Exception {
        for (int i = 0; i < 3; i++) {
            GalleryInfo gi = new GalleryInfo();
            gi.gid = i + 1;
            gi.title = "T" + (i + 1);
            inventoryOnShare.add(gi);
        }
        InventoryPager.Page page = pager.loadPageBounded(SortMode.TITLE_ASC, 0, true);
        assertEquals(3, page.data.size());
        assertEquals("cached ordering must not read per row", 0, readCalls);
    }

    @Test
    public void forgottenRefsLeaveTheOrdering() throws Exception {
        seedRefs(2);
        pager.loadPageBounded(SortMode.DOWNLOAD_DATE_DESC, 0, true);
        pager.forgetRef("1-G1");
        InventoryPager.Page page = pager.loadPageBounded(SortMode.DOWNLOAD_DATE_DESC, 0, false);
        assertEquals(1, page.data.size());
        assertEquals(2L, page.data.get(0).gid);
    }

    @Test
    public void renamedRefsFollowTheirGallery() throws Exception {
        GalleryInfo gi = new GalleryInfo();
        gi.gid = 1;
        gi.title = "Old";
        inventoryOnShare.add(gi);
        pager.loadPageBounded(SortMode.TITLE_ASC, 0, true);

        pager.renameRef("1-Old", "1-New");
        InventoryPager.Page page = pager.loadPageBounded(SortMode.TITLE_ASC, 0, false);
        assertEquals(1, page.data.size());
        assertEquals("the cached record must survive the rename", "Old", page.data.get(0).title);
        assertEquals(0, readCalls);
    }

    @Test
    public void aShareThatCannotBeListedNamesTheShareAndTheReason() {
        listFailure = new IOException("Network error");

        IOException e = assertThrows(IOException.class,
                () -> pager.loadPageBounded(SortMode.DOWNLOAD_DATE_DESC, 0, true));

        assertEquals(GetText.getString(R.string.storage_share_open_failed, "SMB", "Network error"),
                e.getMessage());
        assertSame(listFailure, e.getCause());
    }
}
