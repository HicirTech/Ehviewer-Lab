/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import androidx.annotation.NonNull;

/**
 * A gallery folder located but not yet read; metadata is read lazily per visible row.
 * folderMtime rides the enumeration for free and orders the default view without a metadata read.
 */
public final class GalleryRef {
    @NonNull public final String folderName;
    public final long folderMtime;

    public GalleryRef(@NonNull String folderName, long folderMtime) {
        this.folderName = folderName;
        this.folderMtime = folderMtime;
    }
}
