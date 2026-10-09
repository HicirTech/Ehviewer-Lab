/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.SortMode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;
import jcifs.util.transport.TransportException;

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
    static final Set<String> opened = ConcurrentHashMap.newKeySet();
    static volatile OpenHook onOpen = path -> {};
    static Exception listFailure;

    interface OpenHook {
        void at(String path) throws IOException, InterruptedException;
    }

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
            if (listFailure != null) {
                throw listFailure;
            }
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
            opened.add(real.getPath());
            try {
                onOpen.at(real.getPath());
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            }
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
        GetText.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(NetworkStorageSettings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(NetworkStorageSettings.KEY_SMB_SHARE_NAME, "share");
        Settings.putString(NetworkStorageSettings.KEY_SMB_SHARE_PATH, "");
        Settings.putString(NetworkStorageSettings.KEY_SMB_USERNAME, "");
        existing.clear();
        listings.clear();
        contents.clear();
        createTimes.clear();
        mtimes.clear();
        unreadable.clear();
        opened.clear();
        onOpen = path -> {};
        listFailure = null;
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

    @Test
    public void foreignFoldersAreNotGalleries() throws Exception {
        listings.put(rootPath, new String[]{"42-Answer/", "state/", "misc backups/"});
        List<GalleryRef> refs = SmbInventory.listGalleryRefs();
        assertEquals(1, refs.size());
        assertEquals("the trailing slash jcifs reports must be trimmed",
                "42-Answer", refs.get(0).folderName);
    }

    @Test
    public void theOrderingKeyPrefersCreateTimeAndFallsBackToMtime() throws Exception {
        listings.put(rootPath, new String[]{"1-A/", "2-B/"});
        createTimes.put(rootPath + "1-A/", 1000L);
        mtimes.put(rootPath + "1-A/", 9999L);
        mtimes.put(rootPath + "2-B/", 2000L);
        List<GalleryRef> refs = SmbInventory.listGalleryRefs();
        assertEquals(2, refs.size());
        assertEquals(1000L, refs.get(0).folderMtime);
        assertEquals("no createTime: the enumeration's mtime stands in",
                2000L, refs.get(1).folderMtime);
    }

    @Test
    public void loadInventoryReadsEveryGallery() throws Exception {
        listings.put(rootPath, new String[]{"1-A/", "2-B/"});
        folderWithMetadata("1-A", 1L);
        folderWithMetadata("2-B", 2L);
        List<GalleryInfo> loaded = SmbInventory.loadInventory(SortMode.TITLE_ASC);
        assertEquals(2, loaded.size());
        assertEquals(1L, loaded.get(0).gid);
        assertEquals(2L, loaded.get(1).gid);
    }

    @Test
    public void oneUnreadableGalleryDoesNotLoseTheRest() throws Exception {
        listings.put(rootPath, new String[]{"1-A/", "2-B/", "3-C/"});
        folderWithMetadata("1-A", 1L);
        folderWithMetadata("2-B", 2L);
        folderWithMetadata("3-C", 3L);
        unreadable.add(rootPath + "2-B/" + SmbMetadata.METADATA_FILE);
        List<GalleryInfo> loaded = SmbInventory.loadInventory(SortMode.TITLE_ASC);
        assertEquals(2, loaded.size());
    }

    @Test
    public void aRefWithoutMetadataReadsAsNull() throws Exception {
        listings.put(rootPath, new String[]{"7-G/"});
        existing.add(rootPath + "7-G/");
        List<GalleryRef> refs = SmbInventory.listGalleryRefs();
        assertEquals(1, refs.size());
        assertTrue(SmbInventory.readGalleryInfo(refs.get(0)) == null);
    }

    @Test
    public void pageReadsKeepTheRefOrder() throws Exception {
        listings.put(rootPath, new String[]{"1-A/", "2-B/", "3-C/"});
        folderWithMetadata("1-A", 1L);
        existing.add(rootPath + "2-B/");
        folderWithMetadata("3-C", 3L);
        onOpen = path -> {
            if (path.contains("1-A")) {
                Thread.sleep(200);
            }
        };
        List<GalleryInfo> infos =
                SmbInventory.readGalleryInfos(SmbInventory.listGalleryRefs(), 10_000);
        assertEquals(2, infos.size());
        assertEquals(1L, infos.get(0).gid);
        assertEquals(3L, infos.get(1).gid);
    }

    @Test
    public void pageReadsRunSideBySide() throws Exception {
        Settings.putString(SmbConcurrency.KEY_METADATA, "3");
        listings.put(rootPath, new String[]{"1-A/", "2-B/", "3-C/"});
        folderWithMetadata("1-A", 1L);
        folderWithMetadata("2-B", 2L);
        folderWithMetadata("3-C", 3L);
        CountDownLatch allOpen = new CountDownLatch(3);
        onOpen = path -> {
            allOpen.countDown();
            if (!allOpen.await(2, TimeUnit.SECONDS)) {
                throw new IOException("fixture: reads ran one at a time");
            }
        };
        assertEquals(3, SmbInventory.readGalleryInfos(SmbInventory.listGalleryRefs(), 10_000).size());
    }

    @Test
    public void readsThatKeepFinishingOutlastTheStallLimit() throws Exception {
        Settings.putString(SmbConcurrency.KEY_METADATA, "1");
        String[] names = new String[16];
        for (int i = 0; i < names.length; i++) {
            names[i] = (i + 1) + "-G/";
            folderWithMetadata((i + 1) + "-G", i + 1);
        }
        listings.put(rootPath, names);
        onOpen = path -> Thread.sleep(40);
        // 640 ms in all, never 320 ms without a finished read.
        assertEquals(16, SmbInventory.readGalleryInfos(SmbInventory.listGalleryRefs(), 320).size());
    }

    @Test
    public void aStalledReadFailsThePageAndDropsTheQueuedReads() throws Exception {
        Settings.putString(SmbConcurrency.KEY_METADATA, "1");
        awaitOneInventoryWorker();
        listings.put(rootPath, new String[]{"1-A/", "2-B/", "3-C/"});
        folderWithMetadata("1-A", 1L);
        folderWithMetadata("2-B", 2L);
        folderWithMetadata("3-C", 3L);
        CountDownLatch never = new CountDownLatch(1);
        onOpen = path -> never.await();
        List<GalleryRef> refs = SmbInventory.listGalleryRefs();
        assertThrows(TimeoutException.class, () -> SmbInventory.readGalleryInfos(refs, 200));
        drainInventoryPool();
        assertEquals(Collections.singleton(rootPath + "1-A/" + SmbMetadata.METADATA_FILE), opened);
    }

    /** Idle workers left by earlier tests exit only some time after the pool shrinks. */
    private static void awaitOneInventoryWorker() throws InterruptedException {
        ThreadPoolExecutor pool = SmbInventory.inventoryExecutor();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getPoolSize() > 1) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the inventory pool kept " + pool.getPoolSize() + " workers");
            }
            Thread.sleep(10);
        }
    }

    /** One worker here, so a no-op runs only after every task queued before it. */
    private static void drainInventoryPool() throws Exception {
        SmbInventory.inventoryExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    @Test
    public void aShareThatCannotBeListedSaysWhy() {
        listFailure = refused();

        IOException e = assertThrows(IOException.class, SmbInventory::listGalleryRefs);

        assertEquals(GetText.getString(R.string.error_socket), e.getMessage());
        assertSame("the jcifs chain must stay attached", listFailure, e.getCause());
    }

    @Test
    public void theEagerLoadOfAShareThatCannotBeListedSaysWhy() {
        listFailure = refused();

        IOException e = assertThrows(IOException.class,
                () -> SmbInventory.loadInventory(SortMode.TITLE_ASC));

        assertEquals(GetText.getString(R.string.error_socket), e.getMessage());
    }

    @Test
    public void aShareWithoutTheGalleryFolderIsEmptyNotFailed() throws Exception {
        existing.remove(rootPath);

        assertTrue(SmbInventory.listGalleryRefs().isEmpty());
        assertTrue(SmbInventory.loadInventory(SortMode.TITLE_ASC).isEmpty());
    }

    private static SmbException refused() {
        return new SmbException("Failed to connect: 0.0.0.0<00>/192.0.2.7",
                new TransportException(new java.net.ConnectException("Connection refused")));
    }

    @Test
    public void nothingConfiguredMeansEmptyAnswers() throws Exception {
        Settings.putString(NetworkStorageSettings.KEY_SMB_HOST, "");
        assertTrue(SmbInventory.listGalleryRefs().isEmpty());
        assertTrue(SmbInventory.loadInventory().isEmpty());
    }
}
