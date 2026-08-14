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
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link GallerySpiderStorage} backed by the SMB share, plus the per-gid mark that decides which
 * galleries use it. A thin adapter over the static SMB helpers so {@code SpiderDen} can talk to
 * one interface instead of reaching into the SMB layer from a dozen call sites.
 */
public final class SmbSpiderStorage implements GallerySpiderStorage {

    /**
     * Per-gid intent mark for routing reads/writes to SMB. Replaces the old global
     * {@code Settings.getSmbSaveEnabled()} routing flag — that was leaking phone downloads
     * onto the SMB share whenever the master toggle was on. Now only galleries explicitly
     * marked here (by {@link SmbDirectDownloader} or {@code LocalInventoryScene.openReader})
     * use SMB I/O. Regular DownloadManager downloads always go to phone storage.
     *
     * <p>Lives beside {@link #createIfTarget} because that is the one consumer: the mark and
     * the backend selection it drives are a single seam (#97).
     */
    private static final java.util.Set<Long> SMB_TARGET_GIDS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static void markGidAsSmbTarget(long gid) {
        SMB_TARGET_GIDS.add(gid);
    }

    public static void unmarkGidAsSmbTarget(long gid) {
        SMB_TARGET_GIDS.remove(gid);
    }

    public static boolean isGidMarkedSmbTarget(long gid) {
        return SMB_TARGET_GIDS.contains(gid);
    }

    @NonNull
    private final GalleryInfo info;

    private SmbSpiderStorage(@NonNull GalleryInfo info) {
        this.info = info;
    }

    /**
     * Returns an SMB backend for the gallery iff {@code gid} is currently marked as an SMB target,
     * otherwise null (meaning: use phone storage). The mark is re-checked on every call so that
     * clearing it — e.g. when a download is cancelled — immediately reverts to local storage,
     * preserving the behaviour of the old per-call {@code useSmbStorage()} check.
     *
     * <p>{@code gid} and {@code info} are passed separately to mirror {@code SpiderDen}, which
     * tracks a gid independently of the GalleryInfo it hands to the storage helpers.
     */
    @Nullable
    public static SmbSpiderStorage createIfTarget(@NonNull GalleryInfo info, long gid) {
        return SmbSpiderStorage.isGidMarkedSmbTarget(gid) ? new SmbSpiderStorage(info) : null;
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
