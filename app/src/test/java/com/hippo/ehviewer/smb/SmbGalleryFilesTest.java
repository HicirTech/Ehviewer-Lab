/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jcifs.smb.SmbFile;

/**
 * The per-file half of the SMB layer (#97): page lookup by cached listing, and — the part with
 * the history — the atomic write. #35 was precisely a reader seeing a half-written file and a
 * stale listing calling a just-written page missing; the two tests at the bottom pin the shape
 * that fixed it: nothing publishes until close, and close both renames and invalidates.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbGalleryFilesTest.ShadowSmbFile.class,
                   SmbGalleryFilesTest.ShadowSmbGalleryDirectory.class},
        instrumentedPackages = {"jcifs.smb", "com.hippo.ehviewer.smb"})
public class SmbGalleryFilesTest {

    static final Set<String> filenames = new HashSet<>();
    static final List<String> events = new ArrayList<>();
    static final Map<String, ByteArrayOutputStream> written = new HashMap<>();

    /** The gallery folder's contents come from the directory layer; here they are a fixture. */
    @Implements(SmbGalleryDirectory.class)
    public static class ShadowSmbGalleryDirectory {
        @Implementation
        protected static Set<String> galleryFilenames(GalleryInfo info) {
            return filenames;
        }

        @Implementation
        protected static void invalidateListing(long gid) {
            events.add("invalidate:" + gid);
        }
    }

    @Implements(SmbFile.class)
    public static class ShadowSmbFile {
        @RealObject SmbFile real;

        @Implementation
        protected OutputStream getOutputStream() {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            written.put(real.getPath(), sink);
            return sink;
        }

        @Implementation
        protected void renameTo(jcifs.SmbResource dest, boolean replace) {
            events.add("rename:" + real.getName() + "->" + dest.getName() + ":replace=" + replace);
        }

        @Implementation
        protected void delete() {
            events.add("delete:" + real.getName());
        }
    }

    private final GalleryInfo gallery = SmbGalleryDirectory.lookupKey(42L, "Answer");

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(Settings.KEY_SMB_SHARE_NAME, "share");
        Settings.putString(Settings.KEY_SMB_SHARE_PATH, "");
        Settings.putString(Settings.KEY_SMB_USERNAME, "");
        filenames.clear();
        events.clear();
        written.clear();
    }

    /** "Is page N saved" is a set lookup over the listing, across every supported extension. */
    @Test
    public void containImageAnswersFromTheListingWhateverTheExtension() {
        filenames.add("00000001.webp");
        filenames.add("00000002.jpg");
        assertTrue(SmbGalleryFiles.containImage(gallery, 0));
        assertTrue(SmbGalleryFiles.containImage(gallery, 1));
        assertFalse(SmbGalleryFiles.containImage(gallery, 2));
    }

    /** The preview lookup builds the one matching reference instead of probing per extension. */
    @Test
    public void previewLookupReturnsTheFileTheListingNames() {
        filenames.add("00000003.png");
        SmbFile page = SmbGalleryFiles.findSmbImageFileForPreview(gallery, 2);
        assertNotNull(page);
        assertEquals("00000003.png", page.getName());
        assertNull(SmbGalleryFiles.findSmbImageFileForPreview(gallery, 5));
    }

    /**
     * readAll must hand back the file's exact bytes. The previous readLine() implementation
     * silently dropped terminators, which corrupts pretty-printed metadata.
     */
    @Test
    public void readAllPreservesLineTerminators() throws Exception {
        String text = "{\r\n  \"pages\": 3\n}\n";
        assertEquals(text, SmbGalleryFiles.readAll(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * The #35 shape: while the stream is open, nothing has been renamed and nothing invalidated —
     * no reader can meet a half-written file under its real name.
     */
    @Test
    public void nothingIsPublishedUntilTheStreamCloses() throws Exception {
        SmbFile dir = new SmbFile(SmbConnection.galleryRootUrl() + "42-Answer/",
                SmbConnection.buildContext());
        OutputStream out = SmbGalleryFiles.openAtomicOutputStream(dir, "00000001.jpg", 42L);
        out.write("bytes".getBytes(StandardCharsets.UTF_8));
        out.flush();
        assertTrue("published before close: " + events, events.isEmpty());
        out.close();
        assertEquals(2, events.size());
        assertTrue(events.get(0).startsWith("rename:"));
        assertTrue("the rename must overwrite", events.get(0).endsWith("replace=true"));
        assertEquals("invalidate:42", events.get(1));
    }

    /** Close is idempotent — a double close must not rename (and so re-invalidate) twice. */
    @Test
    public void closingTwicePublishesOnce() throws Exception {
        SmbFile dir = new SmbFile(SmbConnection.galleryRootUrl() + "42-Answer/",
                SmbConnection.buildContext());
        OutputStream out = SmbGalleryFiles.openAtomicOutputStream(dir, "00000001.jpg", 42L);
        out.close();
        out.close();
        assertEquals(2, events.size());
    }

    /** The bytes written go to the temporary name, not the target. */
    @Test
    public void bytesTravelThroughTheTemporaryName() throws Exception {
        SmbFile dir = new SmbFile(SmbConnection.galleryRootUrl() + "42-Answer/",
                SmbConnection.buildContext());
        OutputStream out = SmbGalleryFiles.openAtomicOutputStream(dir, "00000001.jpg", 42L);
        out.write("payload".getBytes(StandardCharsets.UTF_8));
        out.close();
        String tempPath = written.keySet().iterator().next();
        assertTrue("bytes went to " + tempPath,
                tempPath.contains(SmbTempFiles.nameFor("00000001.jpg")));
        assertEquals("payload", written.get(tempPath).toString(StandardCharsets.UTF_8.name()));
    }
}
