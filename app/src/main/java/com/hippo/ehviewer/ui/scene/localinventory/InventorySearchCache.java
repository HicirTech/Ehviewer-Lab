/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import android.content.Context;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.storage.NetworkStorageInventory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Each gallery's record, kept on the phone so a search reads only what changed on the share. */
final class InventorySearchCache {

    private static final String TAG = "InventorySearchCache";
    private static final int SCHEMA_VERSION = 1;
    private static final String DIR = "inventory-search";

    static final class Entry {
        /** Its ordering time drives the date sort, as on the library page. */
        @NonNull
        final GalleryRef ref;
        final long metadataMtime;
        @NonNull
        final GalleryInfo info;

        Entry(@NonNull GalleryRef ref, long metadataMtime, @NonNull GalleryInfo info) {
            this.ref = ref;
            this.metadataMtime = metadataMtime;
            this.info = info;
        }
    }

    /** What a refresh must read; the rest of the entries carry over. */
    static final class Plan {
        @NonNull
        final List<GalleryRef> toRead = new ArrayList<>();
        @NonNull
        final Map<String, Entry> kept = new LinkedHashMap<>();
    }

    @Nullable
    private static InventorySearchCache sCurrent;
    @Nullable
    private static String sCurrentAddress;

    @NonNull
    private final AtomicFile file;
    /** By folder name; null until the file is read. */
    @Nullable
    private volatile Map<String, Entry> entries;

    InventorySearchCache(@NonNull File file) {
        this.file = new AtomicFile(file);
    }

    /** One per share for the process: switching shares must not mix their galleries. */
    @NonNull
    static synchronized InventorySearchCache forShare(@NonNull Context context, @NonNull String address) {
        if (sCurrent == null || !address.equals(sCurrentAddress)) {
            File dir = new File(context.getFilesDir(), DIR);
            sCurrent = new InventorySearchCache(new File(dir, Integer.toHexString(address.hashCode()) + ".json"));
            sCurrentAddress = address;
        }
        return sCurrent;
    }

    /** What the phone already has, without touching the share. */
    @NonNull
    List<Entry> cached() {
        return new ArrayList<>(loaded().values());
    }

    /** Checks every record's mtime and reads only new or changed ones. Worker thread. */
    @NonNull
    synchronized List<Entry> refresh(@NonNull List<GalleryRef> refs, long stallMillis)
            throws InterruptedException, TimeoutException {
        long t0 = SystemClock.elapsedRealtime();
        NetworkStorageInventory inventory = NetworkStorage.active().inventory();
        Map<String, Long> mtimes = inventory.readMetadataMtimes(refs, stallMillis);
        Plan plan = plan(loaded(), refs, mtimes);
        List<GalleryInfo> fresh = plan.toRead.isEmpty()
                ? Collections.<GalleryInfo>emptyList()
                : inventory.readGalleryInfos(plan.toRead, stallMillis);
        Map<String, Entry> next = merge(plan, fresh, mtimes, loaded());
        entries = next;
        save(next);
        Log.i("SmbPerf", "search.cache refs=" + refs.size() + " read=" + plan.toRead.size()
                + " entries=" + next.size() + " " + (SystemClock.elapsedRealtime() - t0) + "ms");
        return new ArrayList<>(next.values());
    }

    /** Pure: an mtime that is missing means the check failed, so the old entry stays. */
    @NonNull
    static Plan plan(@NonNull Map<String, Entry> cached, @NonNull List<GalleryRef> refs,
                     @NonNull Map<String, Long> mtimes) {
        Plan plan = new Plan();
        for (GalleryRef ref : refs) {
            Long mtime = mtimes.get(ref.folderName);
            Entry old = cached.get(ref.folderName);
            if (mtime == null) {
                if (old != null) {
                    plan.kept.put(ref.folderName, new Entry(ref, old.metadataMtime, old.info));
                }
            } else if (mtime != NetworkStorageInventory.NO_METADATA) {
                if (old != null && old.metadataMtime == mtime) {
                    plan.kept.put(ref.folderName, new Entry(ref, old.metadataMtime, old.info));
                } else {
                    plan.toRead.add(ref);
                }
            }
        }
        return plan;
    }

    /** Pure: a record matched to its folder by gid; one that could not be read keeps its old entry. */
    @NonNull
    static Map<String, Entry> merge(@NonNull Plan plan, @NonNull List<GalleryInfo> fresh,
                                    @NonNull Map<String, Long> mtimes,
                                    @NonNull Map<String, Entry> cached) {
        Map<Long, GalleryInfo> byGid = new HashMap<>();
        for (GalleryInfo info : fresh) {
            byGid.put(info.gid, info);
        }
        Map<String, Entry> out = new LinkedHashMap<>(plan.kept);
        for (GalleryRef ref : plan.toRead) {
            GalleryInfo info = byGid.get(NetworkStorage.active().parseGalleryGid(ref.folderName));
            Long mtime = mtimes.get(ref.folderName);
            if (info != null && mtime != null) {
                out.put(ref.folderName, new Entry(ref, mtime, info));
            } else {
                Entry old = cached.get(ref.folderName);
                if (old != null) {
                    out.put(ref.folderName, new Entry(ref, old.metadataMtime, old.info));
                }
            }
        }
        return out;
    }

    @NonNull
    private Map<String, Entry> loaded() {
        Map<String, Entry> current = entries;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (entries == null) {
                entries = load();
            }
            return entries;
        }
    }

    /** A file that cannot be read starts the cache over: the share is the truth. */
    @NonNull
    private Map<String, Entry> load() {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (!file.getBaseFile().exists()) {
            return out;
        }
        try {
            JSONObject root = JSONObject.parseObject(new String(file.readFully(), StandardCharsets.UTF_8));
            if (root == null || root.getIntValue("version") != SCHEMA_VERSION) {
                return out;
            }
            JSONArray array = root.getJSONArray("entries");
            for (int i = 0; array != null && i < array.size(); i++) {
                JSONObject e = array.getJSONObject(i);
                GalleryRef ref = new GalleryRef(e.getString("folder"), e.getLongValue("folderMtime"));
                out.put(ref.folderName, new Entry(ref, e.getLongValue("mtime"),
                        GalleryInfo.galleryInfoFromJson(e.getJSONObject("info"))));
            }
        } catch (Throwable e) {
            Log.w(TAG, "Starting the search cache over: " + file.getBaseFile(), e);
            out.clear();
        }
        return out;
    }

    /** A failed save costs only a fuller read next time. */
    private void save(@NonNull Map<String, Entry> map) {
        JSONArray array = new JSONArray(map.size());
        for (Entry entry : map.values()) {
            JSONObject e = new JSONObject();
            e.put("folder", entry.ref.folderName);
            e.put("folderMtime", entry.ref.folderMtime);
            e.put("mtime", entry.metadataMtime);
            e.put("info", entry.info.toJson());
            array.add(e);
        }
        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        root.put("entries", array);
        FileOutputStream os = null;
        try {
            os = file.startWrite();
            os.write(root.toJSONString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(os);
        } catch (IOException e) {
            if (os != null) {
                file.failWrite(os);
            }
            Log.w(TAG, "Could not save the search cache", e);
        }
    }
}
