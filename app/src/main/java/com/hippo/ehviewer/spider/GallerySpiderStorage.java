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

/**
 * Storage backend for one gallery's pages and spider info — the single extension point SpiderDen
 * routes through. Blocking I/O; worker threads; zero-based page indices.
 */
public interface GallerySpiderStorage {

    /** Ensure the gallery's destination directory exists. Returns false on failure. */
    boolean prepareDir();

    /** Open a stream to write the per-gallery spider-info file, or null on failure.
     * Also called while reading: the resume position (startPage) lives in this file. */
    @Nullable
    OutputStream openSpiderInfoOutputStream();

    /** Open a stream to read the per-gallery spider-info file, or null if absent. */
    @Nullable
    InputStream openSpiderInfoInputStream();

    /** Whether a page image at {@code index} is already stored. */
    boolean containImage(int index);

    /** Remove the stored page image at {@code index}. Returns true if anything was deleted. */
    boolean removeImage(int index);

    /**
     * Open a pipe to write the page image at {@code index}.
     *
     * @param extension file extension (with or without leading dot), or null to let the backend
     *                  pick a default
     */
    @Nullable
    OutputStreamPipe openImageOutputStreamPipe(int index, @Nullable String extension);

    /** Open a pipe to read the stored page image at {@code index}, or null if absent. */
    @Nullable
    InputStreamPipe openImageInputStreamPipe(int index);
}
