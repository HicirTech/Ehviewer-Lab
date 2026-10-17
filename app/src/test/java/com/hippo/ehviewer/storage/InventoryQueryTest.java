/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.widget.AdvanceSearchTable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;

/** What a library search keeps, read the way the online search reads its layout (#185). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class InventoryQueryTest {

    private static GalleryInfo gallery() {
        GalleryInfo gi = new GalleryInfo();
        gi.gid = 1L;
        gi.title = "Summer Vacation";
        gi.titleJpn = "夏休み";
        gi.uploader = "alice";
        gi.category = EhConfig.MANGA;
        gi.rating = 4.5f;
        gi.pages = 15;
        gi.tgList = new ArrayList<>(Arrays.asList("female:glasses", "language:english"));
        return gi;
    }

    private static ListUrlBuilder normal(String keyword) {
        ListUrlBuilder b = new ListUrlBuilder();
        b.setMode(ListUrlBuilder.MODE_NORMAL);
        b.setKeyword(keyword);
        return b;
    }

    private static boolean keeps(String keyword) {
        return InventoryQuery.from(normal(keyword)).matches(gallery());
    }

    @Test
    public void aPlainWordLooksInTitlesTagsAndUploader() {
        assertTrue(keeps("summer"));
        assertTrue(keeps("夏休"));
        assertTrue(keeps("glasses"));
        assertTrue(keeps("alice"));
        assertFalse(keeps("winter"));
    }

    @Test
    public void everyWordMustBeFound() {
        assertTrue(keeps("summer glasses"));
        assertFalse(keeps("summer winter"));
    }

    @Test
    public void caseDoesNotMatter() {
        assertTrue(keeps("SUMMER"));
        assertTrue(keeps("Female:Glasses"));
    }

    @Test
    public void quotesKeepAPhraseTogether() {
        assertTrue(keeps("\"summer vacation\""));
        assertFalse(keeps("\"vacation summer\""));
        assertEquals(Arrays.asList("summer vacation", "x"), InventoryQuery.tokens("\"summer vacation\"  x"));
    }

    @Test
    public void aNamespaceLooksOnlyAtThatNamespace() {
        assertTrue(keeps("female:glasses"));
        assertFalse(keeps("male:glasses"));
        assertFalse("a namespaced word is not looked for in the title", keeps("female:summer"));
    }

    @Test
    public void theSitesShortNamespacesWork() {
        assertTrue(keeps("f:glasses"));
        assertTrue(keeps("l:english"));
    }

    @Test
    public void aDollarAsksForTheWholeTag() {
        assertTrue(keeps("female:glass"));
        assertFalse(keeps("female:glass$"));
        assertTrue(keeps("female:glasses$"));
        assertTrue(keeps("female:\"glasses$\""));
    }

    @Test
    public void aMinusLeavesOut() {
        assertFalse(keeps("summer -glasses"));
        assertTrue(keeps("summer -winter"));
        assertFalse(keeps("-female:glasses"));
    }

    @Test
    public void anUnknownPrefixIsPartOfTheWord() {
        GalleryInfo gi = gallery();
        gi.title = "Re:Zero Days";

        assertTrue(InventoryQuery.from(normal("re:zero")).matches(gi));
    }

    @Test
    public void theUploaderWordLooksAtTheUploader() {
        assertTrue(keeps("uploader:ali"));
        assertTrue(keeps("uploader:alice$"));
        assertFalse(keeps("uploader:ali$"));
        assertFalse(keeps("uploader:summer"));
    }

    @Test
    public void anUploaderSearchWantsTheWholeNameInAnyCase() {
        ListUrlBuilder b = new ListUrlBuilder();
        b.setMode(ListUrlBuilder.MODE_UPLOADER);
        b.setKeyword(" Alice ");
        assertTrue(InventoryQuery.from(b).matches(gallery()));

        b.setKeyword("ali");
        assertFalse(InventoryQuery.from(b).matches(gallery()));
    }

    @Test
    public void aTagSearchLooksOnlyAtTags() {
        ListUrlBuilder b = new ListUrlBuilder();
        b.setMode(ListUrlBuilder.MODE_TAG);
        b.setKeyword("female:glasses");
        assertTrue(InventoryQuery.from(b).matches(gallery()));

        b.setKeyword("summer");
        assertFalse("a title word does not match a tag search", InventoryQuery.from(b).matches(gallery()));
    }

    @Test
    public void theCategoryTableIsReadAsTheOnlineSearchSendsIt() {
        ListUrlBuilder b = normal("");
        // CategoryTable reports the categories left unchecked; the search leaves the checked ones out.
        b.setCategory(EhConfig.ALL_CATEGORY & ~EhConfig.MANGA);
        assertFalse(InventoryQuery.from(b).matches(gallery()));

        b.setCategory(EhConfig.ALL_CATEGORY & ~EhConfig.DOUJINSHI);
        assertTrue(InventoryQuery.from(b).matches(gallery()));

        b.setCategory(EhUtils.NONE);
        assertTrue(InventoryQuery.from(b).matches(gallery()));
    }

    @Test
    public void ratingAndPageBoundsFilter() {
        ListUrlBuilder b = normal("");
        b.setMinRating(5);
        assertFalse(InventoryQuery.from(b).matches(gallery()));
        b.setMinRating(4);
        assertTrue(InventoryQuery.from(b).matches(gallery()));

        b = normal("");
        b.setPageFrom(16);
        assertFalse(InventoryQuery.from(b).matches(gallery()));
        b.setPageFrom(10);
        b.setPageTo(14);
        assertFalse(InventoryQuery.from(b).matches(gallery()));
        b.setPageTo(20);
        assertTrue(InventoryQuery.from(b).matches(gallery()));
    }

    @Test
    public void theAdvancedSwitchesNarrowWherePlainWordsLook() {
        ListUrlBuilder b = normal("summer");
        b.setAdvanceSearch(AdvanceSearchTable.STAGS);
        assertFalse(InventoryQuery.from(b).matches(gallery()));

        b.setKeyword("glasses");
        assertTrue(InventoryQuery.from(b).matches(gallery()));

        b.setKeyword("summer");
        b.setAdvanceSearch(AdvanceSearchTable.SDESC);
        assertTrue("neither name nor tags switched on reads as both",
                InventoryQuery.from(b).matches(gallery()));
    }

    @Test
    public void tagsFallBackToTheShortListOfAnOlderRecord() {
        GalleryInfo gi = gallery();
        gi.tgList = null;
        gi.simpleTags = new String[]{"female:glasses"};

        assertTrue(InventoryQuery.from(normal("female:glasses")).matches(gi));
    }

    @Test
    public void onlyPlainWordsWithNoFiltersCanBeAnsweredFromTitles() {
        assertTrue(InventoryQuery.from(normal("summer vacation")).answersFromTitles());
        assertFalse(InventoryQuery.from(normal("female:glasses")).answersFromTitles());
        assertFalse(InventoryQuery.from(normal("summer -winter")).answersFromTitles());
        assertFalse(InventoryQuery.from(normal("")).answersFromTitles());

        ListUrlBuilder filtered = normal("summer");
        filtered.setMinRating(3);
        assertFalse(InventoryQuery.from(filtered).answersFromTitles());

        ListUrlBuilder tag = new ListUrlBuilder();
        tag.setMode(ListUrlBuilder.MODE_TAG);
        tag.setKeyword("summer");
        assertFalse(InventoryQuery.from(tag).answersFromTitles());
    }

    @Test
    public void aTitleAnswerNeedsEveryWordInTheTitle() {
        InventoryQuery q = InventoryQuery.from(normal("summer VACATION"));

        assertTrue(q.matchesTitle("Summer Vacation"));
        assertFalse(q.matchesTitle("Summer"));
        assertFalse(q.matchesTitle(null));
    }

    @Test
    public void nothingTypedAndNothingFilteredIsNoSearch() {
        assertTrue(InventoryQuery.from(normal("  ")).isEmpty());

        ListUrlBuilder filtered = normal("");
        filtered.setCategory(EhConfig.ALL_CATEGORY & ~EhConfig.MANGA);
        assertFalse(InventoryQuery.from(filtered).isEmpty());
    }

    @Test
    public void searchesThatNeedTheSiteAreRefused() {
        assertTrue(InventoryQuery.supports(ListUrlBuilder.MODE_NORMAL));
        assertTrue(InventoryQuery.supports(ListUrlBuilder.MODE_UPLOADER));
        assertTrue(InventoryQuery.supports(ListUrlBuilder.MODE_TAG));
        assertFalse(InventoryQuery.supports(ListUrlBuilder.MODE_SUBSCRIPTION));
        assertFalse(InventoryQuery.supports(ListUrlBuilder.MODE_IMAGE_SEARCH));

        ListUrlBuilder image = new ListUrlBuilder();
        image.setMode(ListUrlBuilder.MODE_IMAGE_SEARCH);
        assertThrows(IllegalArgumentException.class, () -> InventoryQuery.from(image));
    }
}
