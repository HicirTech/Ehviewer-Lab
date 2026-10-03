/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.spider;

import androidx.annotation.Nullable;

import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.InputStream;
import java.io.OutputStream;

/** Blocking I/O on worker threads; page indices are zero-based. */
public interface GallerySpiderStorage {

    boolean prepareDir();

    /** Also called while reading: the resume position lives in this file. */
    @Nullable
    OutputStream openSpiderInfoOutputStream();

    @Nullable
    InputStream openSpiderInfoInputStream();

    boolean containImage(int index);

    /** True if anything was deleted. */
    boolean removeImage(int index);

    /** {@code extension} may lack its leading dot; null lets the backend pick one. */
    @Nullable
    OutputStreamPipe openImageOutputStreamPipe(int index, @Nullable String extension);

    @Nullable
    InputStreamPipe openImageInputStreamPipe(int index);
}
