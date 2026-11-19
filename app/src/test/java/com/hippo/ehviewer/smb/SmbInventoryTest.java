/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import com.hippo.ehviewer.spider.SpiderInfo;
import com.hippo.ehviewer.storage.GalleryCheck;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.SortMode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.NetworkStorageInventory;
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

import android.util.SparseArray;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
    static final Set<String> uncheckable = new HashSet<>();
    static final Set<String> unlistable = new HashSet<>();
    /** By path; a listed file without an entry holds one byte. */
    static final Map<String, Long> sizes = new HashMap<>();
    static final Set<String> opened = ConcurrentHashMap.newKeySet();
    static volatile OpenHook onOpen = path -> {};
    static volatile OpenHook onExists = path -> {};
    static Exception listFailure;

    interface OpenHook {
        void at(String path) throws IOException, InterruptedException;
    }

    @Implements(SmbFile.class)
    public static class ShadowSmbFile {
        @RealObject SmbFile real;

        @Implementation
        protected boolean exists() throws SmbException {
            try {
                onExists.at(real.getPath());
            } catch (IOException | InterruptedException e) {
                throw new SmbException("fixture: check interrupted", e);
            }
            if (uncheckable.contains(real.getPath())) {
                throw new SmbException("fixture: uncheckable");
            }
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
            if (unlistable.contains(real.getPath())) {
                throw new SmbException("fixture: unlistable");
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
        protected long length() {
            Long size = sizes.get(real.getPath());
            return size != null ? size : 1L;
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
        uncheckable.clear();
        unlistable.clear();
        sizes.clear();
        opened.clear();
        onOpen = path -> {};
        onExists = path -> {};
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

    @Test
    public void metadataMtimesAreByFolderAndMarkTheMissingOnes() throws Exception {
        listings.put(rootPath, new String[]{"1-A/", "2-B/"});
        folderWithMetadata("1-A", 1L);
        mtimes.put(rootPath + "1-A/" + SmbMetadata.METADATA_FILE, 5000L);
        existing.add(rootPath + "2-B/");

        Map<String, Long> found = SmbInventory.readMetadataMtimes(SmbInventory.listGalleryRefs(), 10_000);

        assertEquals(2, found.size());
        assertEquals(Long.valueOf(5000L), found.get("1-A"));
        assertEquals(Long.valueOf(NetworkStorageInventory.NO_METADATA), found.get("2-B"));
    }

    @Test
    public void aFolderThatCannotBeCheckedIsLeftOutNotMarkedMissing() throws Exception {
        listings.put(rootPath, new String[]{"1-A/", "2-B/"});
        folderWithMetadata("1-A", 1L);
        folderWithMetadata("2-B", 2L);
        uncheckable.add(rootPath + "2-B/" + SmbMetadata.METADATA_FILE);

        Map<String, Long> found = SmbInventory.readMetadataMtimes(SmbInventory.listGalleryRefs(), 10_000);

        assertEquals(Collections.singleton("1-A"), found.keySet());
    }

    @Test
    public void aStalledCheckFailsTheSweep() throws Exception {
        Settings.putString(SmbConcurrency.KEY_METADATA, "1");
        awaitOneInventoryWorker();
        listings.put(rootPath, new String[]{"1-A/", "2-B/"});
        folderWithMetadata("1-A", 1L);
        folderWithMetadata("2-B", 2L);
        List<GalleryRef> refs = SmbInventory.listGalleryRefs();
        CountDownLatch never = new CountDownLatch(1);
        onExists = path -> never.await();

        assertThrows(TimeoutException.class, () -> SmbInventory.readMetadataMtimes(refs, 200));
        onExists = path -> {};
        drainInventoryPool();
    }

    /** A gallery folder holding these files, with a metadata.json when one is given. */
    private String galleryFolder(String name, String metadataJson, String... files) {
        String folder = rootPath + name + "/";
        existing.add(folder);
        List<String> listing = new ArrayList<>(Arrays.asList(files));
        if (metadataJson != null) {
            listing.add(SmbMetadata.METADATA_FILE);
            existing.add(folder + SmbMetadata.METADATA_FILE);
            contents.put(folder + SmbMetadata.METADATA_FILE, metadataJson.getBytes(StandardCharsets.UTF_8));
        }
        listings.put(folder, listing.toArray(new String[0]));
        return folder;
    }

    private static void ehviewerSays(String folder, long gid, String token, int pages) {
        SpiderInfo header = new SpiderInfo();
        header.gid = gid;
        header.token = token;
        header.pages = pages;
        header.previewPages = 1;
        header.previewPerPage = 40;
        header.pTokenMap = new SparseArray<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        header.write(out);
        existing.add(folder + ".ehviewer");
        contents.put(folder + ".ehviewer", out.toByteArray());
    }

    private static GalleryCheck check(String folderName) throws IOException {
        return SmbInventory.checkGallery(new GalleryRef(folderName, 0L));
    }

    @Test
    public void aCompleteGalleryHasNothingMissing() throws Exception {
        galleryFolder("1-A", "{\"gid\":1,\"token\":\"t1\",\"title\":\"A\",\"pages\":2}",
                "00000001.jpg", "00000002.webp", "cover.jpg");

        GalleryCheck c = check("1-A");

        assertEquals(2, c.expectedPages);
        assertEquals(0, c.missingPages);
        assertFalse(c.hasProblem());
        assertTrue(c.hasToken());
    }

    @Test
    public void absentAndEmptyPagesAreBothMissing() throws Exception {
        String folder = galleryFolder("1-A", "{\"gid\":1,\"token\":\"t1\",\"title\":\"A\",\"pages\":3}",
                "00000001.jpg", "00000002.jpg", "cover.jpg");
        sizes.put(folder + "00000002.jpg", 0L);

        GalleryCheck c = check("1-A");

        assertEquals(3, c.expectedPages);
        assertEquals("page 2 is empty, page 3 absent", 2, c.missingPages);
    }

    @Test
    public void withoutMetadataTheEhviewerRecordGivesPagesAndToken() throws Exception {
        String folder = galleryFolder("5-Five", null, "00000001.jpg", "cover.jpg");
        ehviewerSays(folder, 5L, "tok5", 4);

        GalleryCheck c = check("5-Five");

        assertTrue(c.recordMissing);
        assertEquals(4, c.expectedPages);
        assertEquals(3, c.missingPages);
        assertTrue(c.hasToken());
        assertEquals("Five", c.title);
    }

    @Test
    public void withNeitherRecordThereIsNoPageCountAndNoToken() throws Exception {
        galleryFolder("6-Six", null, "00000001.jpg");

        GalleryCheck c = check("6-Six");

        assertEquals(GalleryCheck.UNKNOWN_PAGES, c.expectedPages);
        assertTrue(c.hasProblem());
        assertFalse(c.hasToken());
        assertEquals("the folder name stands in for the title", "Six", c.title);
    }

    @Test
    public void aMissingCoverIsAProblem() throws Exception {
        galleryFolder("1-A", "{\"gid\":1,\"token\":\"t1\",\"title\":\"A\",\"pages\":1}", "00000001.jpg");

        GalleryCheck c = check("1-A");

        assertEquals(0, c.missingPages);
        assertTrue(c.coverMissing);
        assertTrue(c.hasProblem());
    }

    @Test
    public void checksKeepTheRefOrderReportEachOneAndLeaveOutTheFailed() throws Exception {
        galleryFolder("1-A", "{\"gid\":1,\"token\":\"t1\",\"title\":\"A\",\"pages\":1}", "00000001.jpg");
        galleryFolder("2-B", "{\"gid\":2,\"token\":\"t2\",\"title\":\"B\",\"pages\":1}");
        galleryFolder("3-C", "{\"gid\":3,\"token\":\"t3\",\"title\":\"C\",\"pages\":1}", "00000001.jpg");
        unlistable.add(rootPath + "2-B/");
        List<GalleryRef> refs = Arrays.asList(
                new GalleryRef("3-C", 0L), new GalleryRef("2-B", 0L), new GalleryRef("1-A", 0L));
        List<String> heard = Collections.synchronizedList(new ArrayList<>());

        List<GalleryCheck> checks = SmbInventory.checkGalleries(refs, 10_000,
                (done, total) -> heard.add(done + "/" + total));

        assertEquals(2, checks.size());
        assertEquals("3-C", checks.get(0).folderName);
        assertEquals("1-A", checks.get(1).folderName);
        assertEquals("every check is heard, the failed one too", 3, heard.size());
        assertTrue(heard.contains("3/3"));
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
