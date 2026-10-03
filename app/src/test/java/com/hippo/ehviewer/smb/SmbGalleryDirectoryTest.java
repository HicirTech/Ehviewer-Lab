/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jcifs.smb.SmbFile;

/** Directory resolution and the listing cache (#97). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbGalleryDirectoryTest.ShadowSmbFile.class},
        instrumentedPackages = {"jcifs.smb"})
public class SmbGalleryDirectoryTest {

    static final Set<String> existing = new HashSet<>();
    static final Map<String, String[]> listings = new HashMap<>();
    static final List<String> wireCalls = new ArrayList<>();

    static boolean listFails;
    static boolean mkdirsFails;

    @Implements(SmbFile.class)
    public static class ShadowSmbFile {
        @RealObject SmbFile real;

        @Implementation
        protected boolean exists() {
            wireCalls.add("exists:" + real.getPath());
            return existing.contains(real.getPath());
        }

        @Implementation
        protected void mkdirs() throws jcifs.smb.SmbException {
            wireCalls.add("mkdirs:" + real.getPath());
            if (mkdirsFails) {
                // The lost race: someone else created it between exists() and mkdirs().
                existing.add(real.getPath());
                throw new jcifs.smb.SmbException(0xC0000035, false); // OBJECT_NAME_COLLISION
            }
            existing.add(real.getPath());
        }

        @Implementation
        protected String[] list() throws jcifs.smb.SmbException {
            wireCalls.add("list:" + real.getPath());
            if (listFails) {
                throw new jcifs.smb.SmbException(0xC00000B5, false); // IO_TIMEOUT: transient
            }
            return listings.get(real.getPath());
        }
    }

    private final GalleryInfo gallery = com.hippo.ehviewer.storage.NetworkStorage.lookupKey(42L, "Answer");

    @Before
    public void setUp() throws Exception {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(NetworkStorageSettings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(NetworkStorageSettings.KEY_SMB_SHARE_NAME, "share");
        Settings.putString(NetworkStorageSettings.KEY_SMB_SHARE_PATH, "");
        Settings.putString(NetworkStorageSettings.KEY_SMB_USERNAME, "");
        existing.clear();
        listings.clear();
        wireCalls.clear();
        listFails = false;
        mkdirsFails = false;
        clearListingCache();
    }

    static void clearListingCache() throws Exception {
        Field cacheField = SmbGalleryDirectory.class.getDeclaredField("LISTING_CACHE");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        Field entries = cache.getClass().getDeclaredField("entries");
        entries.setAccessible(true);
        ((Map<?, ?>) entries.get(cache)).clear();
        Field pending = cache.getClass().getDeclaredField("pending");
        pending.setAccessible(true);
        ((Map<?, ?>) pending.get(cache)).clear();
    }

    @Test
    public void aLookupKeyCarriesOnlyGidAndTitle() {
        GalleryInfo key = com.hippo.ehviewer.storage.NetworkStorage.lookupKey(7L, "Seven");
        assertEquals(7L, key.gid);
        assertEquals("Seven", key.title);
        assertNull(key.thumb);
        assertEquals(0, key.pages);
    }

    @Test
    public void theWriterPathCreatesMissingFolders() throws Exception {
        SmbGalleryDirectory.getGalleryDir(gallery);
        long mkdirs = wireCalls.stream().filter(c -> c.startsWith("mkdirs:")).count();
        assertEquals(2, mkdirs);
    }

    @Test
    public void theReaderPathNeverTouchesTheShare() throws Exception {
        SmbGalleryDirectory.resolveGalleryDir(gallery);
        assertTrue("resolveGalleryDir went on the wire: " + wireCalls, wireCalls.isEmpty());
    }

    @Test
    public void filenamesAreListedOnceThenServedFromTheCache() throws Exception {
        String dirPath = SmbGalleryDirectory.resolveGalleryDir(gallery).getPath();
        listings.put(dirPath, new String[]{"00000001.webp"});

        Set<String> first = SmbGalleryDirectory.galleryFilenames(gallery);
        Set<String> second = SmbGalleryDirectory.galleryFilenames(gallery);
        assertEquals(first, second);
        assertEquals("the second ask should have been a cache hit",
                1, wireCalls.stream().filter(c -> c.startsWith("list:")).count());

        SmbGalleryDirectory.invalidateListing(gallery.gid);
        SmbGalleryDirectory.galleryFilenames(gallery);
        assertEquals("invalidation must force a fresh listing",
                2, wireCalls.stream().filter(c -> c.startsWith("list:")).count());
    }

    @Test
    public void aMissingFolderReadsAsEmptyAndTheMissIsCached() {
        Set<String> names = SmbGalleryDirectory.galleryFilenames(gallery);
        assertTrue(names.isEmpty());
        SmbGalleryDirectory.galleryFilenames(gallery);
        assertEquals(1, wireCalls.stream().filter(c -> c.startsWith("list:")).count());
    }

    @Test
    public void aTransientListingFailureIsNotCached() {
        listFails = true;
        assertTrue(SmbGalleryDirectory.galleryFilenames(gallery).isEmpty());
        SmbGalleryDirectory.galleryFilenames(gallery);
        assertEquals("every call while failing must go back to the share",
                2, wireCalls.stream().filter(c -> c.startsWith("list:")).count());

        listFails = false;
        listings.put(wireCalls.get(0).substring("list:".length()),
                new String[]{"00000001.jpg"});
        assertEquals(1, SmbGalleryDirectory.galleryFilenames(gallery).size());
    }

    @Test
    public void aLostMkdirsRaceIsNotAFailure() throws Exception {
        mkdirsFails = true;
        SmbGalleryDirectory.getGalleryDir(gallery);
        assertTrue("the winner's folder is good enough",
                wireCalls.stream().anyMatch(c -> c.startsWith("mkdirs:")));
    }
}
