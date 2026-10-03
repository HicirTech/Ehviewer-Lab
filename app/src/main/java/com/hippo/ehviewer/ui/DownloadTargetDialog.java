/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.download.PowerDownloadTarget;
import com.hippo.ehviewer.smb.SmbAutoDownloadManager;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.storage.NetworkStorageSettings;

/** The detail page's download button; Power Download's rules only govern the reader. */
public final class DownloadTargetDialog {

    private DownloadTargetDialog() {}

    public static void startDownload(@NonNull Context context, MainActivity activity,
                                     @NonNull GalleryInfo info) {
        if (!NetworkStorageSettings.isEnabled() || !NetworkStorage.active().isConfigured()) {
            CommonOperations.startDownload(activity, info, false);
            return;
        }
        NotificationPermission.onDownloadStart(context);
        CharSequence[] items = {
                PowerDownloadTarget.PHONE.label(context),
                PowerDownloadTarget.NETWORK_STORAGE.label(context)
        };
        new AlertDialog.Builder(context)
                .setTitle(R.string.gallery_download_target_title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        CommonOperations.startDownload(activity, info, false);
                    } else {
                        SmbAutoDownloadManager.getInstance().enqueueManual(context, info);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
