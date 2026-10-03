/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.ui;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;

/** Volume keys turn pages or download, never both; the caller switches the other off on confirm. */
public final class VolumeKeyModeDialog {

    private VolumeKeyModeDialog() {}

    /** @param toDownload true when turning volume-key download on, false for page turning */
    public static void confirm(@NonNull Context context, boolean toDownload,
                               @NonNull Runnable onConfirm, @Nullable Runnable onCancel) {
        boolean[] confirmed = {false};
        new AlertDialog.Builder(context)
                .setMessage(toDownload ? R.string.power_download_volume_replaces_page_turning
                        : R.string.power_download_page_turning_replaces_volume)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    confirmed[0] = true;
                    onConfirm.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(dialog -> {
                    if (!confirmed[0] && onCancel != null) {
                        onCancel.run();
                    }
                })
                .show();
    }
}
