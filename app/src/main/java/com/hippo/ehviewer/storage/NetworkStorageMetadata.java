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

/** The per-gallery record: written at enqueue, enriched in the background, re-synced on demand. */
public interface NetworkStorageMetadata {

    /** A GalleryDetail from the local record alone; detail-only fields get safe empty defaults. */
    @NonNull
    GalleryDetail buildOfflineDetail(@NonNull GalleryInfo info);

    /** Writes a minimal record immediately so the gallery is visible before the download ends. */
    boolean writeMetadataSkeleton(@NonNull GalleryInfo info);

    /** Backfills tags once, in the background; no-op when already present. */
    void enrichLocalMetadataIfMissing(@NonNull Context context, @NonNull GalleryInfo info);

    /**
     * User-requested re-sync from the site; a failed fetch is reported, never papered over.
     *
     * @return the record now stored, or null if nothing was written
     */
    @Nullable
    GalleryInfo resyncMetadata(@NonNull Context context, @NonNull GalleryInfo info);
}
