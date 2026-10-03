/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.download;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.storage.NetworkStorage;

/** {@link #value} is what the settings store. */
public enum PowerDownloadTarget {
    NONE("none"),
    PHONE("phone"),
    NETWORK_STORAGE("network");

    @NonNull
    public final String value;

    PowerDownloadTarget(@NonNull String value) {
        this.value = value;
    }

    @NonNull
    public CharSequence label(@NonNull Context context) {
        switch (this) {
            case PHONE:
                return context.getString(R.string.gallery_download_target_local);
            case NETWORK_STORAGE:
                return context.getString(R.string.gallery_download_target_smb,
                        NetworkStorage.active().displayName());
            default:
                return context.getString(R.string.power_download_target_none);
        }
    }

    @NonNull
    public static PowerDownloadTarget fromValue(@Nullable String value,
                                                @NonNull PowerDownloadTarget fallback) {
        for (PowerDownloadTarget target : values()) {
            if (target.value.equals(value)) {
                return target;
            }
        }
        return fallback;
    }
}
