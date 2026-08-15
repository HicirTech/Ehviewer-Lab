/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.GallerySpiderStorage;
import com.hippo.ehviewer.storage.DownloadState;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.storage.NetworkStorageFiles;
import com.hippo.ehviewer.storage.NetworkStorageInventory;
import com.hippo.ehviewer.storage.NetworkStorageLifecycle;
import com.hippo.ehviewer.storage.NetworkStorageMetadata;
import com.hippo.ehviewer.storage.NetworkStorageStateStore;
import com.hippo.ehviewer.storage.SortMode;
import com.hippo.streampipe.InputStreamPipe;

import java.io.InputStream;
import java.util.List;

import jcifs.smb.SmbFile;

/**
 * The SMB implementation of {@link NetworkStorage}: delegation onto the static SMB classes,
 * which stay the only code that talks jcifs.
 */
public final class SmbNetworkStorage implements NetworkStorage {

    private static final SmbNetworkStorage INSTANCE = new SmbNetworkStorage();

    @NonNull
    public static SmbNetworkStorage instance() {
        return INSTANCE;
    }

    private SmbNetworkStorage() {}

    @NonNull
    @Override
    public String displayName() {
        return "SMB";
    }

    @Override
    public boolean isConfigured() {
        return SmbConnection.isConfigured();
    }

    @NonNull
    @Override
    public String address() {
        return SmbPaths.buildShareUrl(
                com.hippo.ehviewer.Settings.getSmbHost(),
                com.hippo.ehviewer.Settings.getSmbPort(),
                com.hippo.ehviewer.Settings.getSmbShareName(),
                com.hippo.ehviewer.Settings.getSmbSharePath());
    }

    @NonNull
    @Override
    public com.hippo.ehviewer.storage.SelfCheck selfCheck(
            @NonNull com.hippo.ehviewer.storage.ConnectionDraft draft) {
        return SmbSelfCheck.run(draft);
    }

    @NonNull
    @Override
    public String galleryFolderName(@NonNull GalleryInfo info) {
        return SmbPaths.buildGalleryFolderName(info);
    }

    @Override
    public long parseGalleryGid(@NonNull String folderName) {
        return SmbPaths.parseGid(folderName);
    }

    @Nullable
    @Override
    public GallerySpiderStorage spiderStorage(@NonNull GalleryInfo info, long gid) {
        return SmbSpiderStorage.createIfTarget(info, gid);
    }

    private final NetworkStorageInventory inventory = new NetworkStorageInventory() {
        @NonNull
        @Override
        public List<GalleryRef> listGalleryRefs() {
            return SmbInventory.listGalleryRefs();
        }

        @NonNull
        @Override
        public List<GalleryInfo> loadInventory(@NonNull SortMode mode) {
            return SmbInventory.loadInventory(mode);
        }

        @Nullable
        @Override
        public GalleryInfo readGalleryInfo(@NonNull GalleryRef ref) {
            return SmbInventory.readGalleryInfo(ref);
        }

        @Nullable
        @Override
        public GalleryInfo readGalleryMetadata(@NonNull GalleryInfo hint) {
            return SmbInventory.readGalleryMetadata(hint);
        }
    };

    private final NetworkStorageMetadata metadata = new NetworkStorageMetadata() {
        @NonNull
        @Override
        public GalleryDetail buildOfflineDetail(@NonNull GalleryInfo info) {
            return SmbMetadata.buildOfflineDetail(info);
        }

        @Override
        public boolean writeMetadataSkeleton(@NonNull GalleryInfo info) {
            return SmbMetadata.writeMetadataSkeleton(info);
        }

        @Override
        public void enrichLocalMetadataIfMissing(@NonNull Context context, @NonNull GalleryInfo info) {
            SmbMetadata.enrichLocalMetadataIfMissing(context, info);
        }

        @Nullable
        @Override
        public GalleryInfo resyncMetadata(@NonNull Context context, @NonNull GalleryInfo info) {
            return SmbMetadata.resyncMetadata(context, info);
        }
    };

    private final NetworkStorageLifecycle lifecycle = new NetworkStorageLifecycle() {
        @Override
        public boolean deleteGalleryFolder(@NonNull GalleryInfo info) {
            return SmbGalleryLifecycle.deleteGalleryFolder(info);
        }

        @Override
        public boolean isGalleryComplete(@NonNull GalleryInfo info) {
            return SmbGalleryLifecycle.isGalleryComplete(info);
        }

        @Override
        public void finalizeDownloadedGallery(@NonNull Context context, @NonNull GalleryInfo info) {
            SmbGalleryLifecycle.finalizeDownloadedGallery(context, info);
        }
    };

    private final NetworkStorageFiles files = new NetworkStorageFiles() {
        @Nullable
        @Override
        public byte[] readCoverBytes(@NonNull GalleryInfo lookup) {
            return SmbGalleryFiles.readCoverBytes(lookup);
        }

        @Nullable
        @Override
        public InputStream openImageInputStream(@NonNull GalleryInfo lookup, int index) {
            SmbFile remote = SmbGalleryFiles.findSmbImageFileForPreview(lookup, index);
            if (remote == null) {
                return null;
            }
            try {
                return new java.io.BufferedInputStream(
                        remote.getInputStream(), SmbGalleryFiles.SMB_IO_BUFFER);
            } catch (Throwable e) {
                return null;
            }
        }

        @Nullable
        @Override
        public InputStreamPipe openCoverInputStreamPipe(@NonNull GalleryInfo lookup) {
            return SmbGalleryFiles.openSmbCoverInputStreamPipe(lookup);
        }

        @Nullable
        @Override
        public InputStreamPipe openImageInputStreamPipe(@NonNull GalleryInfo lookup, int index) {
            return SmbGalleryFiles.openSmbInputStreamPipe(lookup, index);
        }
    };

    private final NetworkStorageStateStore stateStore = new NetworkStorageStateStore() {
        @NonNull
        @Override
        public List<DownloadState.Published> readAll() {
            return SmbDownloadStateStore.readAll();
        }

        @Override
        public boolean writeSelf(@NonNull DownloadState.ClientState state) {
            return SmbDownloadStateStore.writeSelf(state);
        }

        @Override
        public boolean removeTask(@NonNull String ownerClientId, long gid) {
            return SmbDownloadStateStore.removeTask(ownerClientId, gid);
        }
    };

    @NonNull
    @Override
    public NetworkStorageInventory inventory() {
        return inventory;
    }

    @NonNull
    @Override
    public NetworkStorageMetadata metadata() {
        return metadata;
    }

    @NonNull
    @Override
    public NetworkStorageLifecycle lifecycle() {
        return lifecycle;
    }

    @NonNull
    @Override
    public NetworkStorageFiles files() {
        return files;
    }

    @NonNull
    @Override
    public NetworkStorageStateStore stateStore() {
        return stateStore;
    }
}
