package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.GalleryTagGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Pins the in-memory half of the on-share metadata contract (#42). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbMetadataTest {

    private static GalleryInfo info() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 4035531L;
        info.token = "f47cc446f3";
        info.title = "A gallery with \"quotes\" in the title";
        info.titleJpn = "リザード娘産卵マンガ";
        info.category = 2;
        info.posted = "2026-07-06 20:58";
        info.uploader = "Ruin2020";
        info.rating = 3.83f;
        info.pages = 11;
        info.simpleLanguage = "ZH";
        info.tgList = new ArrayList<>(Arrays.asList("language:chinese", "artist:z-ton"));
        return info;
    }

    private static List<String> tg(String... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    // --- buildTagGroupsFromList ------------------------------------------------------------

    @Test
    public void tagGroups_nullOrEmptyListYieldsNoGroups() {
        assertEquals(0, SmbMetadata.buildTagGroupsFromList(null).length);
        assertEquals(0, SmbMetadata.buildTagGroupsFromList(Collections.emptyList()).length);
    }

    @Test
    public void tagGroups_splitsOnFirstColon() {
        GalleryTagGroup[] groups = SmbMetadata.buildTagGroupsFromList(tg("language:chinese"));

        assertEquals(1, groups.length);
        assertEquals("language", groups[0].groupName);
        assertEquals(1, groups[0].size());
        assertEquals("chinese", groups[0].getTagAt(0));
    }

    @Test
    public void tagGroups_keepsLaterColonsInsideTheTag() {
        // Only the first colon separates; the rest belongs to the tag.
        GalleryTagGroup[] groups = SmbMetadata.buildTagGroupsFromList(tg("other:a:b"));

        assertEquals("other", groups[0].groupName);
        assertEquals("a:b", groups[0].getTagAt(0));
    }

    @Test
    public void tagGroups_ungroupedEntriesFallIntoMisc() {
        GalleryTagGroup[] groups = SmbMetadata.buildTagGroupsFromList(tg("solo", "another"));

        assertEquals(1, groups.length);
        assertEquals("misc", groups[0].groupName);
        assertEquals(2, groups[0].size());
        assertEquals("solo", groups[0].getTagAt(0));
        assertEquals("another", groups[0].getTagAt(1));
    }

    /** Documents a wart rather than endorsing it: an entry starting with ':' is treated as ungrouped and the colon stays part of the tag, because the ungroup */
    @Test
    public void tagGroups_leadingColonIsKeptInTheTag() {
        GalleryTagGroup[] groups = SmbMetadata.buildTagGroupsFromList(tg(":leadingColon"));

        assertEquals("misc", groups[0].groupName);
        assertEquals(":leadingColon", groups[0].getTagAt(0));
    }

    @Test
    public void tagGroups_skipsEntriesWithoutATag() {
        // "language:" has a group but an empty tag; a blank entry carries nothing at all.
        // A bare ":" is *not* skipped — see tagGroups_leadingColonIsKeptInTheTag.
        assertEquals(0, SmbMetadata.buildTagGroupsFromList(tg("language:", "")).length);
    }

    @Test
    public void tagGroups_collectsTagsPerGroupAndKeepsFirstSeenOrder() {
        GalleryTagGroup[] groups = SmbMetadata.buildTagGroupsFromList(
                tg("female:eggs", "language:chinese", "female:lizard girl", "artist:z-ton"));

        assertEquals(3, groups.length);
        assertEquals("female", groups[0].groupName);
        assertEquals("language", groups[1].groupName);
        assertEquals("artist", groups[2].groupName);

        assertEquals(2, groups[0].size());
        assertEquals("eggs", groups[0].getTagAt(0));
        assertEquals("lizard girl", groups[0].getTagAt(1));
    }

    // --- buildOfflineDetail ----------------------------------------------------------------

    @Test
    public void offlineDetail_carriesTheStoredFieldsAcross() {
        GalleryInfo in = info();
        GalleryDetail gd = SmbMetadata.buildOfflineDetail(in);

        assertEquals(in.gid, gd.gid);
        assertEquals(in.token, gd.token);
        assertEquals(in.title, gd.title);
        assertEquals(in.titleJpn, gd.titleJpn);
        assertEquals(in.category, gd.category);
        assertEquals(in.posted, gd.posted);
        assertEquals(in.uploader, gd.uploader);
        assertEquals(in.rating, gd.rating, 0.0001f);
        assertEquals(in.pages, gd.pages);
        assertEquals(in.simpleLanguage, gd.simpleLanguage);
    }

    @Test
    public void offlineDetail_rebuildsTagsFromTgList() {
        GalleryDetail gd = SmbMetadata.buildOfflineDetail(info());

        assertEquals(2, gd.tags.length);
        assertEquals("language", gd.tags[0].groupName);
        assertEquals("artist", gd.tags[1].groupName);
    }

    /**
     * GalleryDetailScene dereferences these without null checks, so the offline path has to
     * supply them or opening a stored gallery crashes.
     */
    @Test
    public void offlineDetail_fillsTheFieldsTheDetailSceneDereferences() {
        GalleryDetail gd = SmbMetadata.buildOfflineDetail(info());

        assertNotNull(gd.comments);
        assertEquals(0, gd.comments.comments.length);
        assertEquals("", gd.size);
        assertEquals("", gd.parent);
        assertEquals("", gd.visible);
        assertEquals("", gd.torrentUrl);
        assertEquals("", gd.archiveUrl);
    }

    @Test
    public void offlineDetail_languageFallsBackToSimpleLanguageThenEmpty() {
        assertEquals("ZH", SmbMetadata.buildOfflineDetail(info()).language);

        GalleryInfo noLanguage = info();
        noLanguage.simpleLanguage = null;
        assertEquals("", SmbMetadata.buildOfflineDetail(noLanguage).language);
    }

    /**
     * The preview grid is capped on purpose: one cell per page would mean one SMB prefetch per
     * page, which froze the detail scene on large galleries (#9).
     */
    @Test
    public void offlineDetail_capsThePreviewCount() {
        GalleryInfo small = info();
        small.pages = 11;
        assertEquals(11, SmbMetadata.buildOfflineDetail(small).previewSet.size());

        GalleryInfo large = info();
        large.pages = 500;
        assertEquals(20, SmbMetadata.buildOfflineDetail(large).previewSet.size());
    }

    @Test
    public void offlineDetail_reportsOnePreviewPageOnlyWhenThereArePages() {
        GalleryInfo withPages = info();
        withPages.pages = 11;
        assertEquals(1, SmbMetadata.buildOfflineDetail(withPages).previewPages);

        GalleryInfo empty = info();
        empty.pages = 0;
        assertEquals(0, SmbMetadata.buildOfflineDetail(empty).previewPages);
    }

    /**
     * The preview set must not hold the detail it belongs to: that cycle
     * (gd.previewSet -> gd) crashes when the detail is parcelled.
     */
    @Test
    public void offlineDetail_previewSetDoesNotReferenceTheDetail() {
        GalleryDetail gd = SmbMetadata.buildOfflineDetail(info());

        assertTrue(gd.previewSet instanceof LocalSmbPreviewSet);
        assertNotSame(gd, gd.previewSet);
    }

    @Test
    public void offlineDetail_reusesAnExistingDetailInsteadOfCopying() {
        GalleryDetail existing = new GalleryDetail();
        existing.gid = 1L;
        existing.pages = 3;

        assertSame(existing, SmbMetadata.buildOfflineDetail(existing));
    }

    // --- keepPathFields ------------------------------------------------------------------------
    //
    // A re-sync overwrites the on-share record with what e-hentai says (#16). One field may not be
    // overwritten, because it is also where the gallery lives on disk.

    /** The rule this exists for. */
    @Test
    public void keepPathFields_refusesToLetANewTitleThrough() {
        GalleryInfo local = new GalleryInfo();
        local.gid = 4111126L;
        local.title = "[Eltonel] Prehistoric Academia";
        GalleryDetail fresh = new GalleryDetail();
        fresh.gid = 4111126L;
        fresh.title = "[Eltonel] Prehistoric Academia [Revised]";

        assertEquals("[Eltonel] Prehistoric Academia",
                SmbMetadata.keepPathFields(fresh, local).title);
    }

    /** The folder name built from the result must still be the one the gallery is stored under. */
    @Test
    public void keepPathFields_leavesTheFolderNameUnchanged() {
        GalleryInfo local = new GalleryInfo();
        local.gid = 4111126L;
        local.title = "A Title";
        String folderBefore = SmbPaths.buildGalleryFolderName(local);
        GalleryDetail fresh = new GalleryDetail();
        fresh.gid = 4111126L;
        fresh.title = "A Completely Different Title";

        assertEquals(folderBefore,
                SmbPaths.buildGalleryFolderName(SmbMetadata.keepPathFields(fresh, local)));
    }

    /** Everything that is not a path is exactly what a re-sync is for, and must come through. */
    @Test
    public void keepPathFields_letsEveryOtherFieldThrough() {
        GalleryInfo local = new GalleryInfo();
        local.gid = 1L;
        local.title = "Kept";
        local.titleJpn = "old jpn";
        local.uploader = "old uploader";
        local.rating = 1.0f;
        local.pages = 10;
        local.category = 2;
        local.simpleLanguage = "JA";
        local.tgList = new ArrayList<>(Collections.singletonList("language:japanese"));

        GalleryDetail fresh = new GalleryDetail();
        fresh.gid = 1L;
        fresh.title = "Changed";
        fresh.titleJpn = "new jpn";
        fresh.uploader = "new uploader";
        fresh.rating = 4.5f;
        fresh.pages = 12;
        fresh.category = 8;
        fresh.simpleLanguage = "ZH";
        fresh.tgList = new ArrayList<>(Arrays.asList("language:chinese", "artist:someone"));

        GalleryInfo merged = SmbMetadata.keepPathFields(fresh, local);

        assertEquals("Kept", merged.title);
        assertEquals("new jpn", merged.titleJpn);
        assertEquals("new uploader", merged.uploader);
        assertEquals(4.5f, merged.rating, 0.0001f);
        assertEquals(12, merged.pages);
        assertEquals(8, merged.category);
        assertEquals("ZH", merged.simpleLanguage);
        assertEquals(Arrays.asList("language:chinese", "artist:someone"), merged.tgList);
    }
}
