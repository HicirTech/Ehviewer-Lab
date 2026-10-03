/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryDetail;
import com.hippo.ehviewer.client.data.GalleryInfo;

public interface NetworkStorageMetadata {

    /** From the stored record alone; detail-only fields get safe empty defaults. */
    @NonNull
    GalleryDetail buildOfflineDetail(@NonNull GalleryInfo info);

    boolean writeMetadataSkeleton(@NonNull GalleryInfo info);

    /** Backfills tags in the background. */
    void enrichLocalMetadataIfMissing(@NonNull Context context, @NonNull GalleryInfo info);

    /** A failed fetch is reported; returns the record now stored, or null if none was written. */
    @Nullable
    GalleryInfo resyncMetadata(@NonNull Context context, @NonNull GalleryInfo info);
}
