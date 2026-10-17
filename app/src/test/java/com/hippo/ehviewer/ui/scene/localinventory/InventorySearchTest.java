/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.smb.SmbInventory;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.InventoryQuery;
import com.hippo.ehviewer.storage.SortMode;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** A library search: the quick answer from the phone, the checked one from the share (#185). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {InventorySearchTest.ShadowSmbInventory.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class InventorySearchTest {

    /** By folder name, in listing order. */
    static final Map<String, GalleryInfo> recordsOnShare = new LinkedHashMap<>();
    static final Map<String, Long> folderTimes = new HashMap<>();
    static boolean checksStall;

    @Implements(SmbInventory.class)
    public static class ShadowSmbInventory {
        @Implementation
        protected static List<GalleryRef> listGalleryRefs() {
            List<GalleryRef> refs = new ArrayList<>();
            for (String folder : recordsOnShare.keySet()) {
                refs.add(new GalleryRef(folder, folderTimes.get(folder)));
            }
            return refs;
        }

        @Implementation
        protected static Map<String, Long> readMetadataMtimes(List<GalleryRef> refs, long stallMillis)
                throws TimeoutException {
            if (checksStall) {
                throw new TimeoutException();
            }
            Map<String, Long> out = new HashMap<>();
            for (GalleryRef ref : refs) {
                out.put(ref.folderName, 1L);
            }
            return out;
        }

        @Implementation
        protected static List<GalleryInfo> readGalleryInfos(List<GalleryRef> refs, long stallMillis) {
            List<GalleryInfo> infos = new ArrayList<>();
            for (GalleryRef ref : refs) {
                infos.add(recordsOnShare.get(ref.folderName));
            }
            return infos;
        }
    }

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private InventorySearchCache cache;

    @Before
    public void setUp() throws Exception {
        GetText.initialize(RuntimeEnvironment.getApplication());
        recordsOnShare.clear();
        folderTimes.clear();
        checksStall = false;
        cache = new InventorySearchCache(new File(tmp.getRoot(), "cache.json"));
        onShare(1, "Summer Days", "alice", 100L, "female:glasses");
        onShare(2, "Winter Nights", "bob", 300L, "female:glasses");
        onShare(3, "Autumn Leaves", "carol", 200L, "male:hat");
    }

    private static void onShare(long gid, String title, String uploader, long folderTime, String tag) {
        GalleryInfo gi = new GalleryInfo();
        gi.gid = gid;
        gi.title = title;
        gi.uploader = uploader;
        gi.tgList = new ArrayList<>(Collections.singletonList(tag));
        String folder = gid + "-" + title;
        recordsOnShare.put(folder, gi);
        folderTimes.put(folder, folderTime);
    }

    private InventorySearch search(String keyword) {
        ListUrlBuilder builder = new ListUrlBuilder();
        builder.setKeyword(keyword);
        return new InventorySearch(InventoryQuery.from(builder), keyword);
    }

    private static List<String> folders(InventoryPager.Ordering ordering) {
        List<String> out = new ArrayList<>();
        for (GalleryRef ref : ordering.refs) {
            out.add(ref.folderName);
        }
        return out;
    }

    /** The library page's date-sorted listing, records read per page. */
    private static InventoryPager.Ordering listing() {
        List<GalleryRef> refs = new ArrayList<>();
        for (String folder : recordsOnShare.keySet()) {
            refs.add(new GalleryRef(folder, folderTimes.get(folder)));
        }
        return new InventoryPager.Ordering(refs, null);
    }

    @Test
    public void theCheckedAnswerSearchesEveryFieldInTheLibrarysOrder() throws Exception {
        InventoryPager.Ordering byDate = search("glasses").checkedAnswer(cache, new InventoryPager(), SortMode.DOWNLOAD_DATE_DESC);
        InventoryPager.Ordering byTitle = search("glasses").checkedAnswer(cache, new InventoryPager(), SortMode.TITLE_ASC);

        assertEquals(Arrays.asList("2-Winter Nights", "1-Summer Days"), folders(byDate));
        assertEquals(Arrays.asList("1-Summer Days", "2-Winter Nights"), folders(byTitle));
        assertSame(recordsOnShare.get("2-Winter Nights"), byDate.infos.get("2-Winter Nights"));
    }

    @Test
    public void aWarmCopyAnswersWithoutTheShare() throws Exception {
        search("glasses").checkedAnswer(cache, new InventoryPager(), SortMode.DOWNLOAD_DATE_DESC);
        recordsOnShare.clear();

        InventoryPager.Ordering quick = search("alice").quickAnswer(cache, null, SortMode.DOWNLOAD_DATE_DESC);

        assertEquals(Collections.singletonList("1-Summer Days"), folders(quick));
    }

    @Test
    public void anEmptyCopyAnswersPlainWordsFromTheFolderNames() {
        InventoryPager.Ordering quick = search("summer").quickAnswer(cache, listing(), SortMode.DOWNLOAD_DATE_DESC);

        assertEquals(Collections.singletonList("1-Summer Days"), folders(quick));
        assertNull("records are read per page, as the listing reads them", quick.infos);
    }

    @Test
    public void anEmptyCopyLeavesTheRestToTheCheck() {
        assertNull(search("female:glasses").quickAnswer(cache, listing(), SortMode.DOWNLOAD_DATE_DESC));
        assertNull("no listing yet", search("summer").quickAnswer(cache, null, SortMode.DOWNLOAD_DATE_DESC));
    }

    @Test
    public void aStalledCheckReadsAsTheShareTimingOut() {
        checksStall = true;

        IOException e = assertThrows(IOException.class,
                () -> search("summer").checkedAnswer(cache, new InventoryPager(), SortMode.DOWNLOAD_DATE_DESC));

        assertEquals(GetText.getString(R.string.local_inventory_timeout, "SMB"), e.getMessage());
    }

    @Test
    public void theSameAnswerHasTheSameGalleriesInOrderFromTheSameRecords() {
        GalleryInfo a = new GalleryInfo();
        GalleryInfo b = new GalleryInfo();
        InventoryPager.Ordering one = ordering(Arrays.asList("1-A", "2-B"), a, b);

        assertTrue(InventorySearch.sameAnswer(one, ordering(Arrays.asList("1-A", "2-B"), a, b)));
        assertFalse(InventorySearch.sameAnswer(one, ordering(Arrays.asList("2-B", "1-A"), b, a)));
        assertFalse("a record read again", InventorySearch.sameAnswer(one,
                ordering(Arrays.asList("1-A", "2-B"), a, new GalleryInfo())));
        assertTrue("records read per page compare by name", InventorySearch.sameAnswer(
                new InventoryPager.Ordering(one.refs, null), one));
    }

    @Test
    public void renamedAndDeletedGalleriesFollowTheAnswerOnScreen() {
        InventorySearch search = search("summer");
        search.shown = ordering(Arrays.asList("1-A", "2-B"), new GalleryInfo(), new GalleryInfo());

        search.renameRef("1-A", "1-A2");
        search.forgetRef("2-B");

        assertEquals(Collections.singletonList("1-A2"), folders(search.shown));
    }

    private static InventoryPager.Ordering ordering(List<String> folders, GalleryInfo... infos) {
        List<GalleryRef> refs = new ArrayList<>();
        Map<String, GalleryInfo> byFolder = new HashMap<>();
        for (int i = 0; i < folders.size(); i++) {
            refs.add(new GalleryRef(folders.get(i), 0L));
            byFolder.put(folders.get(i), infos[i]);
        }
        return new InventoryPager.Ordering(refs, byFolder);
    }
}
