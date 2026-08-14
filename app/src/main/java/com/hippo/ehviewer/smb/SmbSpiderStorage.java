/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.GallerySpiderStorage;
import com.hippo.ehviewer.storage.GalleryTargets;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link GallerySpiderStorage} backed by the SMB share; created only for gids marked in
 * {@link GalleryTargets}. A thin adapter over the static SMB helpers.
 */
public final class SmbSpiderStorage implements GallerySpiderStorage {

    @NonNull
    private final GalleryInfo info;

    private SmbSpiderStorage(@NonNull GalleryInfo info) {
        this.info = info;
    }

    /** An SMB backend iff the gid is marked, re-checked per call so unmarking acts immediately. */
    @Nullable
    static SmbSpiderStorage createIfTarget(@NonNull GalleryInfo info, long gid) {
        return GalleryTargets.isMarked(gid) ? new SmbSpiderStorage(info) : null;
    }

    @Override
    public boolean prepareDir() {
        return SmbGalleryDirectory.prepareGalleryDir(info);
    }

    @Nullable
    @Override
    public OutputStream openSpiderInfoOutputStream() {
        return SmbGalleryFiles.openSpiderInfoOutputStream(info);
    }

    @Nullable
    @Override
    public InputStream openSpiderInfoInputStream() {
        return SmbGalleryFiles.openSpiderInfoInputStream(info);
    }

    @Override
    public boolean containImage(int index) {
        return SmbGalleryFiles.containImage(info, index);
    }

    @Override
    public boolean removeImage(int index) {
        return SmbGalleryFiles.removeImage(info, index);
    }

    @Nullable
    @Override
    public OutputStreamPipe openImageOutputStreamPipe(int index, @Nullable String extension) {
        return SmbGalleryFiles.openSmbOutputStreamPipe(info, index, extension);
    }

    @Nullable
    @Override
    public InputStreamPipe openImageInputStreamPipe(int index) {
        return SmbGalleryFiles.openSmbInputStreamPipe(info, index);
    }
}
