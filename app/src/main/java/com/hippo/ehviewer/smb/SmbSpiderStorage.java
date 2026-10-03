/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.GallerySpiderStorage;
import com.hippo.ehviewer.storage.GalleryTargets;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.InputStream;
import java.io.OutputStream;

public final class SmbSpiderStorage implements GallerySpiderStorage {

    private static final String TAG = "SmbSpiderStorage";

    @NonNull
    private final GalleryInfo info;

    private SmbSpiderStorage(@NonNull GalleryInfo info) {
        this.info = info;
    }

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
        // jcifs on the main thread dies mid-request and poisons the shared transport.
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            Log.w(TAG, "skip spider-info read on main thread gid=" + info.gid);
            return null;
        }
        return SmbGalleryFiles.openSpiderInfoInputStream(info);
    }

    @Override
    public boolean containImage(int index) {
        return SmbGalleryFiles.containImage(info, index);
    }

    @Override
    public boolean removeImage(int index) {
        return SmbGalleryFiles.deleteImage(info, index);
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
