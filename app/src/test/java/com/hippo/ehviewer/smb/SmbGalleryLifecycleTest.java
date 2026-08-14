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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jcifs.smb.SmbFile;

/** The gallery-as-a-whole operations (#97): completeness and deletion, characterised over a nested SmbFile shadow. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbGalleryLifecycleTest.ShadowSmbFile.class},
        instrumentedPackages = {"jcifs.smb"})
public class SmbGalleryLifecycleTest {

    static final Set<String> existing = new HashSet<>();
    static final Map<String, String[]> listings = new HashMap<>();
    static final Map<String, byte[]> contents = new HashMap<>();
    static final List<String> deleted = new ArrayList<>();

    @Implements(SmbFile.class)
    public static class ShadowSmbFile {
        @RealObject SmbFile real;

        @Implementation
        protected boolean exists() {
            return existing.contains(real.getPath());
        }

        @Implementation
        protected String[] list() {
            return listings.get(real.getPath());
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
        protected boolean isDirectory() {
            return real.getPath().endsWith("/");
        }

        @Implementation
        protected void delete() {
            deleted.add(real.getName());
            existing.remove(real.getPath());
        }

        @Implementation
        protected InputStream getInputStream() {
            byte[] bytes = contents.get(real.getPath());
            return new ByteArrayInputStream(bytes != null ? bytes : new byte[0]);
        }
    }

    private final GalleryInfo gallery = SmbGalleryDirectory.lookupKey(42L, "Answer");
    private String dirPath;

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
        deleted.clear();
        SmbGalleryDirectoryTest.clearListingCache();
        dirPath = SmbGalleryDirectory.resolveGalleryDir(gallery).getPath();
    }

    private void metadataSays(int pages) {
        String metadataPath = dirPath + SmbMetadata.METADATA_FILE;
        existing.add(metadataPath);
        contents.put(metadataPath,
                ("{\"gid\":42,\"pages\":" + pages + "}").getBytes(StandardCharsets.UTF_8));
    }

    /** A folder that never existed deletes trivially — and nothing is sent to the share. */
    @Test
    public void deletingWhatWasNeverThereSucceedsWithoutTouchingAnything() {
        assertTrue(SmbGalleryLifecycle.deleteGalleryFolder(gallery));
        assertTrue(deleted.isEmpty());
    }

    /**
     * jcifs refuses to delete a non-empty directory, so children must go first — depth-first,
     * folder last.
     */
    @Test
    public void deletionRemovesTheContentsBeforeTheFolder() {
        existing.add(dirPath);
        listings.put(dirPath, new String[]{"00000001.jpg", "metadata.json"});
        assertTrue(SmbGalleryLifecycle.deleteGalleryFolder(gallery));
        assertEquals(3, deleted.size());
        assertEquals("the folder itself must be deleted last",
                "42-Answer/", deleted.get(2));
    }

    /** Complete means: metadata declares N pages and N image files are actually there. */
    @Test
    public void aGalleryWithEveryDeclaredPageIsComplete() {
        metadataSays(2);
        listings.put(dirPath, new String[]{"00000001.webp", "00000002.jpg", "metadata.json"});
        assertTrue(SmbGalleryLifecycle.isGalleryComplete(gallery));
    }

    /** One missing page is incomplete — this direction failing means abandoned partial saves. */
    @Test
    public void aGalleryMissingAPageIsNotComplete() {
        metadataSays(3);
        listings.put(dirPath, new String[]{"00000001.webp", "00000003.webp", "metadata.json"});
        assertFalse(SmbGalleryLifecycle.isGalleryComplete(gallery));
    }

    /** No metadata on the share means nothing can be declared complete. */
    @Test
    public void aGalleryWithoutMetadataIsNotComplete() {
        listings.put(dirPath, new String[]{"00000001.webp"});
        assertFalse(SmbGalleryLifecycle.isGalleryComplete(gallery));
    }

    /**
     * The declared count comes from the share's metadata even when the caller's copy disagrees —
     * the share is the source of truth, and a stale in-memory {@code pages} must not veto it.
     */
    @Test
    public void theSharesPageCountOutranksTheCallers() {
        gallery.pages = 99;
        metadataSays(1);
        listings.put(dirPath, new String[]{"00000001.webp"});
        assertTrue(SmbGalleryLifecycle.isGalleryComplete(gallery));
    }
}
