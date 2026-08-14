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

    @Implements(SmbFile.class)
    public static class ShadowSmbFile {
        @RealObject SmbFile real;

        @Implementation
        protected boolean exists() {
            wireCalls.add("exists:" + real.getPath());
            return existing.contains(real.getPath());
        }

        @Implementation
        protected void mkdirs() {
            wireCalls.add("mkdirs:" + real.getPath());
            existing.add(real.getPath());
        }

        @Implementation
        protected String[] list() {
            wireCalls.add("list:" + real.getPath());
            return listings.get(real.getPath());
        }
    }

    private final GalleryInfo gallery = SmbGalleryDirectory.lookupKey(42L, "Answer");

    @Before
    public void setUp() throws Exception {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(Settings.KEY_SMB_SHARE_NAME, "share");
        Settings.putString(Settings.KEY_SMB_SHARE_PATH, "");
        Settings.putString(Settings.KEY_SMB_USERNAME, "");
        existing.clear();
        listings.clear();
        wireCalls.clear();
        clearListingCache();
    }

    /** The cache is static process state; every test starts cold, like the androidTest suite does. */
    static void clearListingCache() throws Exception {
        Field cacheField = SmbGalleryDirectory.class.getDeclaredField("LISTING_CACHE");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(null);
        Field entries = cache.getClass().getDeclaredField("entries");
        entries.setAccessible(true);
        ((Map<?, ?>) entries.get(cache)).clear();
    }

    @Test
    public void aLookupKeyCarriesOnlyGidAndTitle() {
        GalleryInfo key = SmbGalleryDirectory.lookupKey(7L, "Seven");
        assertEquals(7L, key.gid);
        assertEquals("Seven", key.title);
        assertNull(key.thumb);
        assertEquals(0, key.pages);
    }

    /** Writers create what is missing: the root and the gallery folder both. */
    @Test
    public void theWriterPathCreatesMissingFolders() throws Exception {
        SmbGalleryDirectory.getGalleryDir(gallery);
        long mkdirs = wireCalls.stream().filter(c -> c.startsWith("mkdirs:")).count();
        assertEquals(2, mkdirs);
    }

    /**
     * The reader path must not touch the share at all — no existence probe, no creation. A single
     * wire call here is how empty {@code <gid>-<title>/} folders get scattered over the share.
     */
    @Test
    public void theReaderPathNeverTouchesTheShare() throws Exception {
        SmbGalleryDirectory.resolveGalleryDir(gallery);
        assertTrue("resolveGalleryDir went on the wire: " + wireCalls, wireCalls.isEmpty());
    }

    /** One listing serves every page check until something structural invalidates it. */
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

    /** A folder that is not there yet is an empty set, and the miss is cached too. */
    @Test
    public void aMissingFolderReadsAsEmptyAndTheMissIsCached() {
        Set<String> names = SmbGalleryDirectory.galleryFilenames(gallery);
        assertTrue(names.isEmpty());
        SmbGalleryDirectory.galleryFilenames(gallery);
        assertEquals(1, wireCalls.stream().filter(c -> c.startsWith("list:")).count());
    }
}
