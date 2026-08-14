/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import android.content.Context;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.client.data.GalleryInfo;

/** The gallery as a whole: completeness, delete, download finalize. Worker threads. */
public interface NetworkStorageLifecycle {

    /** Deletes the gallery's folder recursively. True when deleted or never there. */
    boolean deleteGalleryFolder(@NonNull GalleryInfo info);

    /** Complete = the record declares N pages and N image files are stored. */
    boolean isGalleryComplete(@NonNull GalleryInfo info);

    /** Writes the final record and cover after the last page landed. */
    void finalizeDownloadedGallery(@NonNull Context context, @NonNull GalleryInfo info);
}
