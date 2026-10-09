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

import java.util.List;
import java.util.concurrent.TimeoutException;

/** Blocking IO; worker threads. */
public interface NetworkStorageInventory {

    /** One enumeration, no metadata reads. */
    @NonNull
    List<GalleryRef> listGalleryRefs();

    /** Reads every record: the eager path, for sorts other than by date. */
    @NonNull
    List<GalleryInfo> loadInventory(@NonNull SortMode mode);

    /** In ref order; TimeoutException once no read finishes for stallMillis. */
    @NonNull
    List<GalleryInfo> readGalleryInfos(@NonNull List<GalleryRef> refs, long stallMillis)
            throws InterruptedException, TimeoutException;

    /** For a gallery known only by gid and title. */
    @Nullable
    GalleryInfo readGalleryMetadata(@NonNull GalleryInfo hint);
}
