/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.smb.SmbNetworkStorage;
import com.hippo.ehviewer.spider.GallerySpiderStorage;

import java.io.IOException;

/**
 * A network storage backend (#100): everything protocol-specific sits behind this and its
 * component interfaces. Callers get the active backend from {@link #active()} and never learn
 * which protocol answers.
 */
public interface NetworkStorage {

    /** The protocol value naming SMB; the only one until a second protocol lands. */
    String PROTOCOL_SMB = "smb";

    /** The configured backend. Always SMB until a second protocol has an implementation. */
    @NonNull
    static NetworkStorage active() {
        return SmbNetworkStorage.instance();
    }

    /**
     * Which protocol the stored settings mean. Users from before the selector existed have no
     * protocol key; a configured SMB host identifies them. Empty string = never configured.
     */
    @NonNull
    static String resolveProtocol(@Nullable String storedProtocol, @Nullable String smbHost) {
        if (storedProtocol != null && !storedProtocol.isEmpty()) {
            return storedProtocol;
        }
        if (smbHost != null && !smbHost.isEmpty()) {
            return PROTOCOL_SMB;
        }
        return "";
    }

    /** A minimal GalleryInfo (gid + title), enough to name the gallery's folder. */
    @NonNull
    static GalleryInfo lookupKey(long gid, @Nullable String title) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = title;
        return info;
    }

    /** The protocol's user-facing name ("SMB"); every "%s" in protocol-mentioning copy. */
    @NonNull
    String displayName();

    boolean isConfigured();

    /**
     * Verifies the storage is reachable and prepares the gallery root.
     *
     * @return null when everything is in place, otherwise a user-facing warning
     * @throws IOException when the storage is not usable at all
     */
    @Nullable
    String testConnection() throws IOException;

    /** Returned by {@link #parseGalleryGid} when the name is not a gallery folder's. */
    long NOT_A_GALLERY = -1L;

    /** The gallery's folder name — sanitisation rules are backend-specific. */
    @NonNull
    String galleryFolderName(@NonNull GalleryInfo info);

    /** The gid encoded in a folder name, or {@link #NOT_A_GALLERY} — the inverse naming rule. */
    long parseGalleryGid(@NonNull String folderName);

    /** Per-gallery page/spider-info IO for the spider, or null when the gid is not a target. */
    @Nullable
    GallerySpiderStorage spiderStorage(@NonNull GalleryInfo info, long gid);

    @NonNull
    NetworkStorageInventory inventory();

    @NonNull
    NetworkStorageMetadata metadata();

    @NonNull
    NetworkStorageLifecycle lifecycle();

    @NonNull
    NetworkStorageFiles files();

    @NonNull
    NetworkStorageStateStore stateStore();
}
