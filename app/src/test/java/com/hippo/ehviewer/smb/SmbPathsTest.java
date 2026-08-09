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

/**
 * Unit tests for the share-URL construction extracted into {@link SmbPaths}. The whole reason this
 * logic was pulled out of {@code SmbStorage} is so the share-name encoding (issue #2) can be checked
 * without a live share or Settings — plain JUnit, no Android.
 *
 * <p>Runs under Robolectric only because {@link SmbPaths#buildGalleryFolderName} reaches
 * {@code android.text.TextUtils} through {@code FileUtils.sanitizeFilename}. That used to be a
 * reason to leave it untested — the note here said Robolectric could not pick an SDK for
 * targetSdk 30 — but the project is on 35 and Robolectric 4.14.1 now, and the rest of the suite
 * already runs this way. Covering it matters: {@link SmbPaths#isGalleryFolderName} has to accept
 * whatever that builder emits, and nothing else would catch the two drifting apart.
 */
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
        // URLEncoder would emit "+" for a space; buildShareUrl converts it back to %20 so the
        // result is a valid smb URL rather than form-encoded.
        assertEquals("smb://host/Public%20Documents/",
                SmbPaths.buildShareUrl("host", "445", "Public Documents", "/"));
    }

    @Test
    public void shareUrl_reservedCharInShareEncoded() {
        // '$' is reserved and must be percent-encoded (%24); it must not survive raw.
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
        // Pure helper must not NPE on nulls even though Settings never hands it any.
        assertEquals("smb:///media",
                SmbPaths.buildShareUrl(null, null, "media", null));
    }

    // --- isGalleryFolderName -----------------------------------------------------------------
    //
    // The share root is never only galleries, and the enumeration cannot afford to check each
    // directory for a metadata.json. So "is this ours" is decided from the name alone, and these
    // pin what that decision does at the edges.

    @Test
    public void galleryFolder_acceptsWhatBuildGalleryFolderNameProduces() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 4035531L;
        info.title = "[Artist] A Title (Convention) [English]";
        assertTrue(SmbPaths.isGalleryFolderName(SmbPaths.buildGalleryFolderName(info)));
    }

    /** The untitled fallback still has to be recognised as a gallery. */
    @Test
    public void galleryFolder_acceptsTheUntitledFallback() {
        GalleryInfo info = new GalleryInfo();
        info.gid = 7L;
        info.title = null;
        assertEquals("7-gallery", SmbPaths.buildGalleryFolderName(info));
        assertTrue(SmbPaths.isGalleryFolderName("7-gallery"));
    }

    /**
     * The directories that actually turn up next to the galleries. None of these begin with a
     * dot, which is why skipping hidden entries would not have been enough.
     */
    @Test
    public void galleryFolder_rejectsWhatNasSoftwareLeavesBehind() {
        assertFalse("Synology thumbnails", SmbPaths.isGalleryFolderName("@eaDir"));
        assertFalse("Synology recycle bin", SmbPaths.isGalleryFolderName("#recycle"));
        assertFalse("ext4", SmbPaths.isGalleryFolderName("lost+found"));
        assertFalse("NetApp", SmbPaths.isGalleryFolderName(".snapshot"));
        assertFalse("our own state dir", SmbPaths.isGalleryFolderName("state"));
        assertFalse("our own gallery dir", SmbPaths.isGalleryFolderName("download"));
    }

    /** A title that happens to start with digits must not be mistaken for a gid. */
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

    /** A title containing its own dashes is ordinary; only the first one delimits the gid. */
    @Test
    public void galleryFolder_acceptsDashesInsideTheTitle() {
        assertTrue(SmbPaths.isGalleryFolderName("123-a-b-c"));
    }

    @Test
    public void galleryFolder_rejectsNullAndEmpty() {
        assertFalse(SmbPaths.isGalleryFolderName(null));
        assertFalse(SmbPaths.isGalleryFolderName(""));
    }
}
