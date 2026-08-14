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

/** The storage as a list of galleries. Blocking IO; worker threads. */
public interface NetworkStorageInventory {

    /** All gallery folders from one enumeration, no metadata reads — first-paint cheap. */
    @NonNull
    List<GalleryRef> listGalleryRefs();

    /** The whole library read and sorted; the eager path sorts other than by date need. */
    @NonNull
    List<GalleryInfo> loadInventory(@NonNull SortMode mode);

    /** One folder's record, or null when unreadable. */
    @Nullable
    GalleryInfo readGalleryInfo(@NonNull GalleryRef ref);

    /** The record for a gallery known by gid + title, or null. */
    @Nullable
    GalleryInfo readGalleryMetadata(@NonNull GalleryInfo hint);
}
