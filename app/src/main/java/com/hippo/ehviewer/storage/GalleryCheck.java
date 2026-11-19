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

/** One gallery folder's files measured against its record (#203). */
public final class GalleryCheck {

    public static final int UNKNOWN_PAGES = -1;

    @NonNull public final String folderName;
    /** The record's title, else the one in the folder name. */
    @NonNull public final String title;
    /** Null when neither record names the gallery: there is no token to repair it with. */
    @Nullable public final GalleryInfo info;
    public final int expectedPages;
    /** Pages absent or stored empty; 0 while the page count is unknown. */
    public final int missingPages;
    public final boolean recordMissing;
    public final boolean coverMissing;

    public GalleryCheck(@NonNull String folderName, @NonNull String title, @Nullable GalleryInfo info,
                        int expectedPages, int missingPages, boolean recordMissing, boolean coverMissing) {
        this.folderName = folderName;
        this.title = title;
        this.info = info;
        this.expectedPages = expectedPages;
        this.missingPages = missingPages;
        this.recordMissing = recordMissing;
        this.coverMissing = coverMissing;
    }

    public boolean hasProblem() {
        return expectedPages == UNKNOWN_PAGES || missingPages > 0 || recordMissing || coverMissing;
    }

    public boolean hasToken() {
        return info != null && info.token != null && !info.token.isEmpty();
    }
}
