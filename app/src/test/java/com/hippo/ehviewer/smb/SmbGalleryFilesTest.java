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
import com.hippo.ehviewer.storage.NetworkStorageSettings;

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

/** The per-file half of the SMB layer (#97): page lookup by cached listing, and the atomic write. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbGalleryFilesTest.ShadowSmbFile.class,
                   SmbGalleryFilesTest.ShadowSmbGalleryDirectory.class},
        instrumentedPackages = {"jcifs.smb", "com.hippo.ehviewer.smb"})
public class SmbGalleryFilesTest {

    static final Set<String> filenames = new HashSet<>();
    static final List<String> events = new ArrayList<>();
    static final Map<String, ByteArrayOutputStream> written = new HashMap<>();

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

        @Implementation
        protected static void noteWritten(long gid, String name) {
            events.add("note:" + gid + ":" + name);
        }
    }

    @Implements(SmbFile.class)
    public static class ShadowSmbFile {
        static boolean renameFails;

        @RealObject SmbFile real;

        @Implementation
        protected boolean exists() {
            return true;
        }

        @Implementation
        protected OutputStream getOutputStream() {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            written.put(real.getPath(), sink);
            return sink;
        }

        @Implementation
        protected void renameTo(jcifs.SmbResource dest, boolean replace) throws jcifs.smb.SmbException {
            if (renameFails) {
                throw new jcifs.smb.SmbException(0xC0000022, false);
            }
            events.add("rename:" + real.getName() + "->" + dest.getName() + ":replace=" + replace);
        }

        @Implementation
        protected void delete() {
            events.add("delete:" + real.getName());
        }
    }

    private final GalleryInfo gallery = com.hippo.ehviewer.storage.NetworkStorage.lookupKey(42L, "Answer");

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(NetworkStorageSettings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(NetworkStorageSettings.KEY_SMB_SHARE_NAME, "share");
        Settings.putString(NetworkStorageSettings.KEY_SMB_SHARE_PATH, "");
        Settings.putString(NetworkStorageSettings.KEY_SMB_USERNAME, "");
        filenames.clear();
        events.clear();
        written.clear();
        ShadowSmbFile.renameFails = false;
    }

    @Test
    public void containImageAnswersFromTheListingWhateverTheExtension() {
        filenames.add("00000001.webp");
        filenames.add("00000002.jpg");
        assertTrue(SmbGalleryFiles.containImage(gallery, 0));
        assertTrue(SmbGalleryFiles.containImage(gallery, 1));
        assertFalse(SmbGalleryFiles.containImage(gallery, 2));
    }

    @Test
    public void previewLookupReturnsTheFileTheListingNames() {
        filenames.add("00000003.png");
        SmbFile page = SmbGalleryFiles.findSmbImageFileForPreview(gallery, 2);
        assertNotNull(page);
        assertEquals("00000003.png", page.getName());
        assertNull(SmbGalleryFiles.findSmbImageFileForPreview(gallery, 5));
    }

    @Test
    public void readAllPreservesLineTerminators() throws Exception {
        String text = "{\r\n  \"pages\": 3\n}\n";
        assertEquals(text, SmbGalleryFiles.readAll(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

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
        assertEquals("note:42:00000001.jpg", events.get(1));
    }

    @Test
    public void aFailedPublishInvalidatesInsteadOfNoting() throws Exception {
        SmbFile dir = new SmbFile(SmbConnection.galleryRootUrl() + "42-Answer/",
                SmbConnection.buildContext());
        OutputStream out = SmbGalleryFiles.openAtomicOutputStream(dir, "00000001.jpg", 42L);
        out.write("bytes".getBytes(StandardCharsets.UTF_8));
        ShadowSmbFile.renameFails = true;
        try {
            out.close();
            assertTrue("close should have thrown", false);
        } catch (java.io.IOException expected) {
        }
        assertEquals("invalidate:42", events.get(0));
        assertTrue("the temporary must be cleaned up",
                events.contains("delete:" + SmbTempFiles.nameFor("00000001.jpg")));
    }

    @Test
    public void closingTwicePublishesOnce() throws Exception {
        SmbFile dir = new SmbFile(SmbConnection.galleryRootUrl() + "42-Answer/",
                SmbConnection.buildContext());
        OutputStream out = SmbGalleryFiles.openAtomicOutputStream(dir, "00000001.jpg", 42L);
        out.close();
        out.close();
        assertEquals(2, events.size());
    }

    /** The listing fixture is empty, so only the in-memory echo can answer. */
    @Test
    public void theReadAfterAWriteIsServedFromMemory() throws Exception {
        writePageThroughPipe(gallery, 0, "page bytes");
        com.hippo.streampipe.InputStreamPipe pipe =
                SmbGalleryFiles.openSmbInputStreamPipe(gallery, 0);
        assertNotNull("the echo must answer without the share", pipe);
        pipe.obtain();
        assertEquals("page bytes", SmbGalleryFiles.readAll(pipe.open()));
        pipe.close();
        pipe.release();
    }

    @Test
    public void theEchoIsConsumedByItsFirstRead() throws Exception {
        GalleryInfo other = com.hippo.ehviewer.storage.NetworkStorage.lookupKey(43L, "Other");
        writePageThroughPipe(other, 1, "once");
        assertNotNull(SmbGalleryFiles.openSmbInputStreamPipe(other, 1));
        assertNull(SmbGalleryFiles.openSmbInputStreamPipe(other, 1));
    }

    @Test
    public void aFailedPublishLeavesNoEcho() throws Exception {
        GalleryInfo failed = com.hippo.ehviewer.storage.NetworkStorage.lookupKey(44L, "Failed");
        ShadowSmbFile.renameFails = true;
        writePageThroughPipe(failed, 0, "never published");
        assertNull(SmbGalleryFiles.openSmbInputStreamPipe(failed, 0));
    }

    @Test
    public void deletingAPageRemovesThePublishedFileAndForgetsTheListing() throws Exception {
        filenames.add("00000001.jpg");
        assertTrue(SmbGalleryFiles.deleteImage(gallery, 0));
        assertTrue("the published file must be deleted: " + events,
                events.contains("delete:00000001.jpg"));
        assertTrue("the listing must be invalidated: " + events,
                events.contains("invalidate:42"));
    }

    @Test
    public void deletingAPageRemovesEveryExtensionItHas() {
        filenames.add("00000001.jpg");
        filenames.add("00000001.png");
        assertTrue(SmbGalleryFiles.deleteImage(gallery, 0));
        assertTrue("jpg must go: " + events, events.contains("delete:00000001.jpg"));
        assertTrue("png must go too: " + events, events.contains("delete:00000001.png"));
    }

    @Test
    public void deletingAnAbsentPageIsANoOp() {
        assertFalse(SmbGalleryFiles.deleteImage(gallery, 0));
        assertTrue(events.isEmpty());
    }

    @Test
    public void deletingAPagePurgesItsEcho() throws Exception {
        GalleryInfo purged = com.hippo.ehviewer.storage.NetworkStorage.lookupKey(45L, "Purged");
        writePageThroughPipe(purged, 0, "truncated bytes");
        SmbGalleryFiles.deleteImage(purged, 0);
        assertNull("the echo must not survive the delete",
                SmbGalleryFiles.openSmbInputStreamPipe(purged, 0));
    }

    @Test
    public void forgettingAGalleryDropsItsEchoes() throws Exception {
        GalleryInfo gone = com.hippo.ehviewer.storage.NetworkStorage.lookupKey(46L, "Gone");
        writePageThroughPipe(gone, 0, "cancelled bytes");
        SmbGalleryFiles.forgetGallery(gone.gid);
        assertNull(SmbGalleryFiles.openSmbInputStreamPipe(gone, 0));
    }

    private static void writePageThroughPipe(GalleryInfo info, int index, String payload)
            throws Exception {
        com.hippo.streampipe.OutputStreamPipe pipe =
                SmbGalleryFiles.openSmbOutputStreamPipe(info, index, ".jpg");
        assertNotNull(pipe);
        pipe.obtain();
        pipe.open().write(payload.getBytes(StandardCharsets.UTF_8));
        pipe.close();
        pipe.release();
    }

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
