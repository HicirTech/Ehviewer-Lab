/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Unit tests for the share-URL construction in SmbPaths. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbPathsTest {

    @Test
    public void shareUrl_defaultPortOmitted() {
        assertEquals("smb://192.168.1.10/media/ehviewer/",
                SmbPaths.buildShareUrl("192.168.1.10", "445", "media", "/ehviewer/"));
    }

    @Test
    public void shareUrl_nonDefaultPortIncluded() {
        assertEquals("smb://192.168.1.10:4450/media/ehviewer/",
                SmbPaths.buildShareUrl("192.168.1.10", "4450", "media", "/ehviewer/"));
    }

    @Test
    public void shareUrl_emptyPortOmitted() {
        assertEquals("smb://host/media/",
                SmbPaths.buildShareUrl("host", "", "media", "/"));
    }

    @Test
    public void shareUrl_spaceInShareEncodedAsPercent20() {
        assertEquals("smb://host/Public%20Documents/",
                SmbPaths.buildShareUrl("host", "445", "Public Documents", "/"));
    }

    @Test
    public void shareUrl_reservedCharInShareEncoded() {
        assertEquals("smb://host/Family%24/",
                SmbPaths.buildShareUrl("host", "445", "Family$", "/"));
    }

    @Test
    public void shareUrl_emptyShareNotEncoded() {
        assertEquals("smb://host//",
                SmbPaths.buildShareUrl("host", "445", "", "/"));
    }

    @Test
    public void shareUrl_nullHostAndPathTreatedAsEmpty() {
        assertEquals("smb:///media",
                SmbPaths.buildShareUrl(null, null, "media", null));
    }

    @Test
    public void galleryRoot_appendsTheGalleryDirectory() {
        assertEquals("smb://host/media/ehviewer/download/",
                SmbPaths.buildGalleryRootUrl("smb://host/media/ehviewer/"));
    }

    @Test
    public void galleryRoot_insertsTheSeparatorWhenTheShareUrlLacksOne() {
        assertEquals("smb://host/media/download/",
                SmbPaths.buildGalleryRootUrl("smb://host/media"));
    }

    @Test
    public void galleryRoot_composesWithBuildShareUrl() {
        assertEquals("smb://192.168.1.10/media/ehviewer/download/",
                SmbPaths.buildGalleryRootUrl(
                        SmbPaths.buildShareUrl("192.168.1.10", "445", "media", "/ehviewer/")));
    }

    @Test
    public void galleryRoot_isBelowTheShareRootNotInsteadOfIt() {
        String share = SmbPaths.buildShareUrl("host", "445", "media", "/");
        assertEquals("smb://host/media/", share);
        assertTrue(SmbPaths.buildGalleryRootUrl(share).startsWith(share));
    }

    @Test
    public void galleryFolder_acceptsWhatBuildGalleryFolderNameProduces() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 4035531L;
        info.title = "[Artist] A Title (Convention) [English]";
        assertTrue(SmbPaths.isGalleryFolderName(SmbPaths.buildGalleryFolderName(info)));
    }

    @Test
    public void galleryFolder_acceptsTheUntitledFallback() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 7L;
        info.title = null;
        assertEquals("7-gallery", SmbPaths.buildGalleryFolderName(info));
        assertTrue(SmbPaths.isGalleryFolderName("7-gallery"));
    }

    @Test
    public void galleryFolder_rejectsWhatNasSoftwareLeavesBehind() {
        assertFalse("Synology thumbnails", SmbPaths.isGalleryFolderName("@eaDir"));
        assertFalse("Synology recycle bin", SmbPaths.isGalleryFolderName("#recycle"));
        assertFalse("ext4", SmbPaths.isGalleryFolderName("lost+found"));
        assertFalse("NetApp", SmbPaths.isGalleryFolderName(".snapshot"));
        assertFalse("our own state dir", SmbPaths.isGalleryFolderName("state"));
        assertFalse("our own gallery dir", SmbPaths.isGalleryFolderName("download"));
    }

    @Test
    public void galleryFolder_requiresDigitsBeforeTheDash() {
        assertFalse(SmbPaths.isGalleryFolderName("12ab-title"));
        assertFalse(SmbPaths.isGalleryFolderName("-title"));
        assertFalse(SmbPaths.isGalleryFolderName("abc-title"));
    }

    @Test
    public void galleryFolder_requiresSomethingAfterTheDash() {
        assertFalse(SmbPaths.isGalleryFolderName("123-"));
        assertFalse(SmbPaths.isGalleryFolderName("123"));
    }

    @Test
    public void galleryFolder_acceptsDashesInsideTheTitle() {
        assertTrue(SmbPaths.isGalleryFolderName("123-a-b-c"));
    }

    @Test
    public void galleryFolder_rejectsNullAndEmpty() {
        assertFalse(SmbPaths.isGalleryFolderName(null));
        assertFalse(SmbPaths.isGalleryFolderName(""));
    }

    @Test
    public void parseGid_recoversWhatBuildGalleryFolderNameEncoded() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 4035531L;
        info.title = "[Artist] A Title (Convention) [English]";

        assertEquals(4035531L, SmbPaths.parseGid(SmbPaths.buildGalleryFolderName(info)));
    }

    @Test
    public void parseGid_stopsAtTheFirstDash() {
        assertEquals(123L, SmbPaths.parseGid("123-a-b-c"));
    }

    @Test
    public void parseGid_refusesEverythingIsGalleryFolderNameRefuses() {
        String[] notGalleries = {
                null, "", "@eaDir", "#recycle", "state", "download",
                "12ab-title", "-title", "abc-title", "123-", "123",
        };
        for (String name : notGalleries) {
            assertFalse("isGalleryFolderName accepts " + name, SmbPaths.isGalleryFolderName(name));
            assertEquals("parseGid accepts " + name,
                    SmbPaths.NOT_A_GALLERY, SmbPaths.parseGid(name));
        }
    }

    @Test
    public void parseGid_refusesANumberTooLargeToBeAGid() {
        String huge = "99999999999999999999999-title";

        assertTrue("the name itself looks like ours", SmbPaths.isGalleryFolderName(huge));
        assertEquals(SmbPaths.NOT_A_GALLERY, SmbPaths.parseGid(huge));
    }

    @Test
    public void parseGid_handlesGidsBeyondIntRange() {
        assertEquals(3_000_000_000L, SmbPaths.parseGid("3000000000-title"));
    }

    @Test
    public void folderName_theOverloadAgreesWithTheRecordVersion() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 4035531L;
        info.title = "[Artist] A Title (Convention) [English]";

        assertEquals(SmbPaths.buildGalleryFolderName(info),
                SmbPaths.buildGalleryFolderName(info.gid, info.title));
    }

    @Test
    public void folderName_aRenamedFolderIsStillOursAndStillCarriesTheGid() {
        String renamed = SmbPaths.buildGalleryFolderName(4035531L, "A Completely New Title");

        assertTrue(SmbPaths.isGalleryFolderName(renamed));
        assertEquals(4035531L, SmbPaths.parseGid(renamed));
    }

    @Test
    public void folderName_theOverloadFallsBackTheSameWayOnAnEmptyTitle() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 7L;
        info.title = "";

        assertEquals(SmbPaths.buildGalleryFolderName(info),
                SmbPaths.buildGalleryFolderName(7L, ""));
        assertEquals(SmbPaths.buildGalleryFolderName(7L, null),
                SmbPaths.buildGalleryFolderName(7L, ""));
    }
}
