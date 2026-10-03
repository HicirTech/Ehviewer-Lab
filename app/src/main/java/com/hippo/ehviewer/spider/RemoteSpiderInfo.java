/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.spider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.util.ExceptionUtils;

import java.io.InputStream;
import java.io.OutputStream;

/** The spider info kept with a gallery on network storage, beside the download folder's copy. */
final class RemoteSpiderInfo {

    private RemoteSpiderInfo() {}

    /** Null when the gallery is not on network storage or its info cannot be read. */
    @Nullable
    static SpiderInfo read(@NonNull GalleryInfo info) {
        GallerySpiderStorage remote = NetworkStorage.active().spiderStorage(info, info.gid);
        if (remote == null) {
            return null;
        }
        InputStream is = remote.openSpiderInfoInputStream();
        try {
            return SpiderInfo.read(is);
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    static void write(@NonNull GalleryInfo info, @NonNull SpiderInfo spiderInfo) {
        GallerySpiderStorage remote = NetworkStorage.active().spiderStorage(info, info.gid);
        if (remote == null) {
            return;
        }
        OutputStream os = remote.openSpiderInfoOutputStream();
        if (os == null) {
            return;
        }
        try {
            spiderInfo.write(os);
        } catch (Throwable e) {
            ExceptionUtils.throwIfFatal(e);
            // Ignore, as SpiderQueen does for the download folder's copy.
        }
    }
}
