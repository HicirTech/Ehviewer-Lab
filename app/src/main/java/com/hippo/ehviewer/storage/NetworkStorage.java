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

public interface NetworkStorage {

    String PROTOCOL_SMB = "smb";

    @NonNull
    static NetworkStorage active() {
        return SmbNetworkStorage.instance();
    }

    /** "" when never configured; pre-selector settings with an SMB host mean SMB. */
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

    /** Only gid and title are set: enough to name the gallery's folder. */
    @NonNull
    static GalleryInfo lookupKey(long gid, @Nullable String title) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = title;
        return info;
    }

    @NonNull
    String displayName();

    boolean isConfigured();

    /** For display only; the format is backend-specific. */
    @NonNull
    String address();

    /** Must not touch the live configuration or any cached connection state. */
    @NonNull
    SelfCheck selfCheck(@NonNull ConnectionDraft draft);

    long NOT_A_GALLERY = -1L;

    @NonNull
    String galleryFolderName(@NonNull GalleryInfo info);

    /** Inverse of {@link #galleryFolderName}; {@link #NOT_A_GALLERY} for any other name. */
    long parseGalleryGid(@NonNull String folderName);

    /** Null when the gid is not a target. */
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
