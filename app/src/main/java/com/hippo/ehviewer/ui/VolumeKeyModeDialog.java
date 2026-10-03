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

/**
 * The volume keys either turn pages or download (#159), never both: turning one on while the
 * other is on asks first. The caller switches the other one off when the user confirms.
 */
public final class VolumeKeyModeDialog {

    private VolumeKeyModeDialog() {}

    /**
     * @param toDownload true when volume-key download is being turned on, false when volume-key
     *                   page turning is
     * @param onCancel   runs when the dialog goes away without a confirmation
     */
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
