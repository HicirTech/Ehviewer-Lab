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

/** Blocking, for worker threads; a gallery is named by {@link NetworkStorage#lookupKey}. */
public interface NetworkStorageFiles {

    /** Into memory, nowhere else. */
    @Nullable
    byte[] readCoverBytes(@NonNull GalleryInfo lookup);

    /** A buffered stream. */
    @Nullable
    InputStream openImageInputStream(@NonNull GalleryInfo lookup, int index);

    /** A decode shim: native decoders need a real fd. */
    @Nullable
    InputStreamPipe openCoverInputStreamPipe(@NonNull GalleryInfo lookup);

    @Nullable
    InputStreamPipe openImageInputStreamPipe(@NonNull GalleryInfo lookup, int index);
}
