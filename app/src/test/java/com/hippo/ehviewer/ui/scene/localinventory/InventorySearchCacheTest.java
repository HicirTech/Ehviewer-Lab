/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbInventory;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.NetworkStorageInventory;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The phone-side copy of the library's records that a search reads (#185). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {InventorySearchCacheTest.ShadowSmbInventory.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class InventorySearchCacheTest {

    static final Map<String, Long> mtimesOnShare = new HashMap<>();
    static final List<String> read = new ArrayList<>();

    @Implements(SmbInventory.class)
    public static class ShadowSmbInventory {
        @Implementation
        protected static Map<String, Long> readMetadataMtimes(List<GalleryRef> refs, long stallMillis) {
            Map<String, Long> out = new HashMap<>();
            for (GalleryRef ref : refs) {
                Long mtime = mtimesOnShare.get(ref.folderName);
                if (mtime != null) {
                    out.put(ref.folderName, mtime);
                }
            }
            return out;
        }

        @Implementation
        protected static List<GalleryInfo> readGalleryInfos(List<GalleryRef> refs, long stallMillis) {
            List<GalleryInfo> infos = new ArrayList<>();
            for (GalleryRef ref : refs) {
                read.add(ref.folderName);
                infos.add(info(ref.folderName, "read " + mtimesOnShare.get(ref.folderName)));
            }
            return infos;
        }
    }

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    private File file;

    @Before
    public void setUp() throws Exception {
        mtimesOnShare.clear();
        read.clear();
        file = new File(tmp.getRoot(), "cache.json");
    }

    private static GalleryInfo info(String folder, String title) {
        GalleryInfo gi = new GalleryInfo();
        gi.gid = Long.parseLong(folder.split("-")[0]);
        gi.title = title;
        return gi;
    }

    private static GalleryRef ref(String folder) {
        return new GalleryRef(folder, 1000L);
    }

    private static Map<String, InventorySearchCache.Entry> cachedAt(String folder, long mtime) {
        Map<String, InventorySearchCache.Entry> cached = new HashMap<>();
        cached.put(folder, new InventorySearchCache.Entry(ref(folder), mtime, info(folder, "old")));
        return cached;
    }

    private static Map<String, Long> mtime(String folder, long mtime) {
        return Collections.singletonMap(folder, mtime);
    }

    @Test
    public void aNewGalleryIsRead() {
        InventorySearchCache.Plan plan = InventorySearchCache.plan(
                Collections.<String, InventorySearchCache.Entry>emptyMap(),
                Collections.singletonList(ref("1-A")), mtime("1-A", 100L));

        assertEquals(Collections.singletonList(ref("1-A").folderName), names(plan.toRead));
        assertTrue(plan.kept.isEmpty());
    }

    @Test
    public void anUnchangedGalleryIsKeptNotRead() {
        InventorySearchCache.Plan plan = InventorySearchCache.plan(
                cachedAt("1-A", 100L), Collections.singletonList(ref("1-A")), mtime("1-A", 100L));

        assertTrue(plan.toRead.isEmpty());
        assertEquals("old", plan.kept.get("1-A").info.title);
    }

    @Test
    public void aChangedGalleryIsReadAgain() {
        InventorySearchCache.Plan plan = InventorySearchCache.plan(
                cachedAt("1-A", 100L), Collections.singletonList(ref("1-A")), mtime("1-A", 200L));

        assertEquals(Collections.singletonList("1-A"), names(plan.toRead));
    }

    @Test
    public void aGalleryWithoutMetadataOrGoneFromTheShareIsDropped() {
        InventorySearchCache.Plan noMetadata = InventorySearchCache.plan(
                cachedAt("1-A", 100L), Collections.singletonList(ref("1-A")),
                mtime("1-A", NetworkStorageInventory.NO_METADATA));
        InventorySearchCache.Plan gone = InventorySearchCache.plan(
                cachedAt("1-A", 100L), Collections.<GalleryRef>emptyList(),
                Collections.<String, Long>emptyMap());

        assertTrue(noMetadata.toRead.isEmpty() && noMetadata.kept.isEmpty());
        assertTrue(gone.toRead.isEmpty() && gone.kept.isEmpty());
    }

    @Test
    public void aFailedCheckKeepsWhatThePhoneHad() {
        InventorySearchCache.Plan plan = InventorySearchCache.plan(
                cachedAt("1-A", 100L), Collections.singletonList(ref("1-A")),
                Collections.<String, Long>emptyMap());

        assertTrue(plan.toRead.isEmpty());
        assertEquals("old", plan.kept.get("1-A").info.title);
    }

    @Test
    public void aRecordIsMatchedToItsFolderByGid() {
        InventorySearchCache.Plan plan = new InventorySearchCache.Plan();
        plan.toRead.add(ref("1-A"));
        plan.toRead.add(ref("2-B"));

        Map<String, InventorySearchCache.Entry> merged = InventorySearchCache.merge(plan,
                Arrays.asList(info("2-B", "two"), info("1-A", "one")),
                mapOf("1-A", 10L, "2-B", 20L), Collections.<String, InventorySearchCache.Entry>emptyMap());

        assertEquals("one", merged.get("1-A").info.title);
        assertEquals(20L, merged.get("2-B").metadataMtime);
    }

    @Test
    public void aRecordThatCouldNotBeReadKeepsItsOldEntry() {
        InventorySearchCache.Plan plan = new InventorySearchCache.Plan();
        plan.toRead.add(ref("1-A"));

        Map<String, InventorySearchCache.Entry> merged = InventorySearchCache.merge(plan,
                Collections.<GalleryInfo>emptyList(), mtime("1-A", 200L), cachedAt("1-A", 100L));

        assertEquals("old", merged.get("1-A").info.title);
        assertEquals("the old mtime, so the next refresh tries again", 100L, merged.get("1-A").metadataMtime);
    }

    @Test
    public void aRefreshReadsOnlyWhatChangedSinceTheLastOne() throws Exception {
        List<GalleryRef> refs = Arrays.asList(ref("1-A"), ref("2-B"));
        mtimesOnShare.put("1-A", 100L);
        mtimesOnShare.put("2-B", 100L);
        InventorySearchCache cache = new InventorySearchCache(file);

        assertEquals(2, cache.refresh(refs, 1000).size());
        assertEquals(2, read.size());

        read.clear();
        cache.refresh(refs, 1000);
        assertTrue(read.isEmpty());

        mtimesOnShare.put("2-B", 200L);
        List<InventorySearchCache.Entry> entries = cache.refresh(refs, 1000);
        assertEquals(Collections.singletonList("2-B"), read);
        assertEquals(2, entries.size());
    }

    @Test
    public void theCopyOutlivesTheProcess() throws Exception {
        mtimesOnShare.put("1-A", 100L);
        new InventorySearchCache(file).refresh(Collections.singletonList(ref("1-A")), 1000);

        List<InventorySearchCache.Entry> again = new InventorySearchCache(file).cached();

        assertEquals(1, again.size());
        assertEquals("1-A", again.get(0).ref.folderName);
        assertEquals(100L, again.get(0).metadataMtime);
        assertEquals("read 100", again.get(0).info.title);
    }

    @Test
    public void anUnreadableFileStartsTheCopyOver() throws Exception {
        try (FileOutputStream os = new FileOutputStream(file)) {
            os.write("{not json".getBytes(StandardCharsets.UTF_8));
        }

        assertTrue(new InventorySearchCache(file).cached().isEmpty());
    }

    @Test
    public void eachShareHasItsOwnCopy() {
        InventorySearchCache a = InventorySearchCache.forShare(RuntimeEnvironment.getApplication(), "smb://a/s");
        InventorySearchCache b = InventorySearchCache.forShare(RuntimeEnvironment.getApplication(), "smb://b/s");

        assertNotSame(a, b);
        assertSame(b, InventorySearchCache.forShare(RuntimeEnvironment.getApplication(), "smb://b/s"));
    }

    private static List<String> names(List<GalleryRef> refs) {
        List<String> out = new ArrayList<>();
        for (GalleryRef r : refs) {
            out.add(r.folderName);
        }
        return out;
    }

    private static Map<String, Long> mapOf(String k1, long v1, String k2, long v2) {
        Map<String, Long> m = new HashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
