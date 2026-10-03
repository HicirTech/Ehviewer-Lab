/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.download;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.smb.SmbAutoDownloadManager;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.ui.CommonOperations;
import com.hippo.ehviewer.ui.NotificationPermission;

/** A quiet request speaks only when a download starts or fails. */
public final class PowerDownloader {

    private PowerDownloader() {}

    /** A local import is its own only copy. */
    public static boolean canDownload(@Nullable GalleryInfo info) {
        return info != null && !SmbDirectDownloader.isLocalImport(info);
    }

    public static void download(@NonNull Context context, @NonNull GalleryInfo info,
                                @NonNull PowerDownloadTarget target, boolean quiet) {
        if (!canDownload(info)) {
            return;
        }
        switch (target) {
            case PHONE:
                downloadToPhone(context, info, quiet);
                break;
            case NETWORK_STORAGE:
                if (quiet) {
                    SmbAutoDownloadManager.getInstance().enqueueQuietly(context, info);
                } else {
                    SmbAutoDownloadManager.getInstance().enqueueManual(context, info);
                }
                break;
            case NONE:
                break;
        }
    }

    /** The download list would restart even a finished download and say "added" again. */
    private static void downloadToPhone(@NonNull Context context, @NonNull GalleryInfo info,
                                        boolean quiet) {
        int state = EhApplication.getDownloadManager(context).getDownloadState(info.gid);
        if (state == DownloadInfo.STATE_WAIT || state == DownloadInfo.STATE_DOWNLOAD) {
            if (!quiet) {
                toast(context, R.string.power_download_already_downloading);
            }
            return;
        }
        if (state == DownloadInfo.STATE_FINISH) {
            if (!quiet) {
                toast(context, R.string.power_download_already_downloaded);
            }
            return;
        }
        NotificationPermission.onDownloadStart(context);
        CommonOperations.startDownloadWithoutAsking(context, info);
        toast(context, R.string.added_to_download_list);
    }

    private static void toast(@NonNull Context context, @StringRes int text) {
        Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT).show();
    }
}
