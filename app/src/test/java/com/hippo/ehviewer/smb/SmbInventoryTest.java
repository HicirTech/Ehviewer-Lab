/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jcifs.smb.SmbFile;

/** The share as a list (#97). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbInventoryTest.ShadowSmbFile.class},
        instrumentedPackages = {"jcifs.smb"})
public class SmbInventoryTest {

    static final Set<String> existing = new HashSet<>();
    static final Map<String, String[]> listings = new HashMap<>();
    static final Map<String, byte[]> contents = new HashMap<>();
    static final Map<String, Long> createTimes = new HashMap<>();
    static final Map<String, Long> mtimes = new HashMap<>();
    static final Set<String> unreadable = new HashSet<>();

    @Implements(SmbFile.class)
    public static class ShadowSmbFile {
        @RealObject SmbFile real;

        @Implementation
        protected boolean exists() {
            return existing.contains(real.getPath());
        }

        @Implementation
        protected boolean isDirectory() {
            return real.getPath().endsWith("/");
        }

        @Implementation
        protected SmbFile[] listFiles() throws Exception {
            String[] names = listings.get(real.getPath());
            if (names == null) {
                return null;
            }
            SmbFile[] out = new SmbFile[names.length];
            for (int i = 0; i < names.length; i++) {
                out[i] = new SmbFile(real, names[i]);
            }
            return out;
        }

        @Implementation
        protected long createTime() {
            Long t = createTimes.get(real.getPath());
            return t != null ? t : 0L;
        }

        @Implementation
        protected long lastModified() {
            Long t = mtimes.get(real.getPath());
            return t != null ? t : 0L;
        }

        @Implementation
        protected InputStream getInputStream() throws IOException {
            if (unreadable.contains(real.getPath())) {
                throw new IOException("fixture: unreadable");
            }
            byte[] bytes = contents.get(real.getPath());
            return new ByteArrayInputStream(bytes != null ? bytes : new byte[0]);
        }
    }

    private String rootPath;

    @Before
    public void setUp() throws Exception {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(Settings.KEY_SMB_SHARE_NAME, "share");
        Settings.putString(Settings.KEY_SMB_SHARE_PATH, "");
        Settings.putString(Settings.KEY_SMB_USERNAME, "");
        existing.clear();
        listings.clear();
        contents.clear();
        createTimes.clear();
        mtimes.clear();
        unreadable.clear();
        rootPath = SmbConnection.galleryRootUrl();
        existing.add(rootPath);
    }

    private void folderWithMetadata(String name, long gid) {
        String folder = rootPath + name + "/";
        String metadata = folder + SmbMetadata.METADATA_FILE;
        existing.add(folder);
        existing.add(metadata);
        contents.put(metadata,
                ("{\"gid\":" + gid + ",\"title\":\"" + name + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** Not every directory on the share is a gallery: state/, download/ siblings and whatever else the NAS carries must not be counted — they inflate the pag */
    @Test
    public void foreignFoldersAreNotGalleries() {
        listings.put(rootPath, new String[]{"42-Answer/", "state/", "misc backups/"});
        List<SmbInventory.GalleryRef> refs = SmbInventory.listGalleryRefs();
        assertEquals(1, refs.size());
        assertEquals("the trailing slash jcifs reports must be trimmed",
                "42-Answer", refs.get(0).folderName);
    }

    /**
     * The ordering key is the folder's creation time — reading a gallery bumps its mtime and
     * must not bump its place — with mtime only as the fallback when createTime is absent.
     */
    @Test
    public void theOrderingKeyPrefersCreateTimeAndFallsBackToMtime() {
        listings.put(rootPath, new String[]{"1-A/", "2-B/"});
        createTimes.put(rootPath + "1-A/", 1000L);
        mtimes.put(rootPath + "1-A/", 9999L);
        mtimes.put(rootPath + "2-B/", 2000L);
        List<SmbInventory.GalleryRef> refs = SmbInventory.listGalleryRefs();
        assertEquals(2, refs.size());
        assertEquals(1000L, refs.get(0).folderMtime);
        assertEquals("no createTime: the enumeration's mtime stands in",
                2000L, refs.get(1).folderMtime);
    }

    /** The eager load reads every gallery's metadata and returns them all. */
    @Test
    public void loadInventoryReadsEveryGallery() {
        listings.put(rootPath, new String[]{"1-A/", "2-B/"});
        folderWithMetadata("1-A", 1L);
        folderWithMetadata("2-B", 2L);
        List<GalleryInfo> loaded = SmbInventory.loadInventory(SmbSortMode.TITLE_ASC);
        assertEquals(2, loaded.size());
        assertEquals(1L, loaded.get(0).gid);
        assertEquals(2L, loaded.get(1).gid);
    }

    /** One unreadable gallery must not lose the others. */
    @Test
    public void oneUnreadableGalleryDoesNotLoseTheRest() {
        listings.put(rootPath, new String[]{"1-A/", "2-B/", "3-C/"});
        folderWithMetadata("1-A", 1L);
        folderWithMetadata("2-B", 2L);
        folderWithMetadata("3-C", 3L);
        unreadable.add(rootPath + "2-B/" + SmbMetadata.METADATA_FILE);
        List<GalleryInfo> loaded = SmbInventory.loadInventory(SmbSortMode.TITLE_ASC);
        assertEquals(2, loaded.size());
    }

    /** A folder without metadata is located by the lazy path, then reads as null, not as a crash. */
    @Test
    public void aRefWithoutMetadataReadsAsNull() {
        listings.put(rootPath, new String[]{"7-G/"});
        existing.add(rootPath + "7-G/");
        List<SmbInventory.GalleryRef> refs = SmbInventory.listGalleryRefs();
        assertEquals(1, refs.size());
        assertTrue(SmbInventory.readGalleryInfo(refs.get(0)) == null);
    }

    /** An unconfigured share is an empty list everywhere, never an exception. */
    @Test
    public void nothingConfiguredMeansEmptyAnswers() {
        Settings.putString(Settings.KEY_SMB_HOST, "");
        assertTrue(SmbInventory.listGalleryRefs().isEmpty());
        assertTrue(SmbInventory.loadInventory().isEmpty());
    }
}
