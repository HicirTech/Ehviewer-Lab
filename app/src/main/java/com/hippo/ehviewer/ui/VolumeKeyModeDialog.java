/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.ui;

import android.content.Context;
import android.widget.CompoundButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.download.PowerDownloadSettings;

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

    /** For the settings screen's page-turning switch: turning it on turns volume-key download off. */
    public static void guardPageTurning(@NonNull Context context,
                                        @Nullable TwoStatePreference volumePage) {
        if (volumePage == null) {
            return;
        }
        volumePage.setOnPreferenceChangeListener((preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue) && PowerDownloadSettings.isVolumeEnabled()) {
                confirm(context, false, () -> {
                    PowerDownloadSettings.putVolumeEnabled(false);
                    volumePage.setChecked(true);
                }, null);
                return false;
            }
            return true;
        });
    }

    /** For the reader's page-turning switch, saved later with its menu: unchecks it unless confirmed. */
    public static void confirmPageTurning(@NonNull CompoundButton volumePage) {
        if (volumePage.isChecked() && PowerDownloadSettings.isVolumeEnabled()) {
            confirm(volumePage.getContext(), false, () -> {}, () -> volumePage.setChecked(false));
        }
    }
}
