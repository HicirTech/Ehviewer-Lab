/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.ui.scene.download.part;

import android.content.Context;
import android.content.res.Resources;
import android.text.format.DateUtils;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.smb.SmbTaskInfo;
import com.hippo.ehviewer.ui.scene.download.DownloadsScene;

final class SmbTaskRowBinder {

    private SmbTaskRowBinder() {}

    static void bindOwner(@NonNull DownloadAdapter.DownloadHolder holder,
                          @NonNull DownloadInfo info, @Nullable Context context) {
        if (!SmbTaskInfo.isSmb(info) || ((SmbTaskInfo) info).mine) {
            holder.getSmbOwner().setVisibility(View.GONE);
            return;
        }
        SmbTaskInfo smb = (SmbTaskInfo) info;
        String text = smb.deviceName;
        if (context != null && smb.lastSeenMillis > 0L) {
            CharSequence ago = DateUtils.getRelativeTimeSpanString(
                    smb.lastSeenMillis, System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS);
            text = context.getString(R.string.smb_task_owner_last_seen, smb.deviceName, ago);
        }
        holder.getSmbOwner().setText(text);
        holder.getSmbOwner().setVisibility(View.VISIBLE);
    }

    /** An UNKNOWN chip on a just-enqueued skeleton would read as a fact. */
    static void hideAbsentFields(@NonNull DownloadAdapter.DownloadHolder holder,
                                 @NonNull DownloadInfo info) {
        if (!SmbTaskInfo.isSmb(info)) {
            return;
        }
        if (info.uploader == null || info.uploader.isEmpty()) {
            holder.getUploader().setVisibility(View.GONE);
        }
        if (info.rating <= 0f) {
            holder.getRating().setVisibility(View.GONE);
        }
        if (info.category == EhUtils.UNKNOWN) {
            holder.getCategory().setVisibility(View.GONE);
        }
    }

    static void hideControlsWeCannotHonour(@NonNull DownloadAdapter.DownloadHolder holder,
                                           @NonNull DownloadInfo info) {
        if (SmbTaskInfo.isSmb(info)
                && !SmbTaskInfo.isActionable(info)
                && !SmbTaskInfo.canTakeOver(info)) {
            holder.getStart().setVisibility(View.GONE);
            holder.getStop().setVisibility(View.GONE);
        }
    }

    @Nullable
    static String orphanStateText(@NonNull DownloadInfo info, @NonNull Resources resources) {
        return SmbTaskInfo.canTakeOver(info)
                ? resources.getString(R.string.smb_task_owner_offline)
                : null;
    }

    /** No speed figure: nobody measures another device's rate. */
    static boolean bindProgress(@NonNull DownloadAdapter.DownloadHolder holder,
                                @NonNull DownloadInfo info) {
        if (!SmbTaskInfo.isSmb(info)) {
            return false;
        }
        holder.getSpeed().setVisibility(View.GONE);
        hideControlsWeCannotHonour(holder, info);
        return true;
    }

    static boolean handleStartClick(@NonNull DownloadInfo info, @NonNull DownloadsScene scene) {
        if (!SmbTaskInfo.isSmb(info)) {
            return false;
        }
        if (SmbTaskInfo.isActionable(info)) {
            SmbDirectDownloader.getInstance().resume(info.gid);
        } else if (SmbTaskInfo.canTakeOver(info)) {
            scene.smbDelegate().confirmTakeOver((SmbTaskInfo) info);
        }
        return true;
    }

    static boolean handleStopClick(@NonNull DownloadInfo info) {
        if (!SmbTaskInfo.isSmb(info)) {
            return false;
        }
        if (SmbTaskInfo.isActionable(info)) {
            SmbDirectDownloader.getInstance().pause(info.gid);
        }
        return true;
    }
}
