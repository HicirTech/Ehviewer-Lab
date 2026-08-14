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
import com.hippo.streampipe.InputStreamPipe;

import java.io.InputStream;

/**
 * Read access to the files inside one gallery's folder, for caches and viewers. The gallery is
 * named by a lookup key ({@link NetworkStorage#lookupKey}); all calls block, worker threads.
 */
public interface NetworkStorageFiles {

    /** One cover into memory, nowhere else, or null when there is none. */
    @Nullable
    byte[] readCoverBytes(@NonNull GalleryInfo lookup);

    /** A buffered stream of the page image at {@code index}, or null when absent. */
    @Nullable
    InputStream openImageInputStream(@NonNull GalleryInfo lookup, int index);

    /** The cover as a decode shim (native decoders need a real fd), or null when absent. */
    @Nullable
    InputStreamPipe openCoverInputStreamPipe(@NonNull GalleryInfo lookup);

    /** The page image at {@code index} as a decode shim, or null when absent. */
    @Nullable
    InputStreamPipe openImageInputStreamPipe(@NonNull GalleryInfo lookup, int index);
}
