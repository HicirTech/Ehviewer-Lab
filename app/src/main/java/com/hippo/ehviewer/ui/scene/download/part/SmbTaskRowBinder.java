/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.ui.scene.download.part;

import android.content.Context;
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

/** The SMB exceptions to download-row binding; stateless (#59, #95). */
final class SmbTaskRowBinder {

    private SmbTaskRowBinder() {}

    /** Owner device + last-heartbeat age (the whole liveness signal); silent for own tasks. */
    static void bindOwner(@NonNull DownloadAdapter.DownloadHolder holder,
                          @NonNull DownloadInfo info, @Nullable Context context) {
        if (!SmbTaskInfo.isSmb(info) || ((SmbTaskInfo) info).mine) {
            holder.smbOwner.setVisibility(View.GONE);
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
        holder.smbOwner.setText(text);
        holder.smbOwner.setVisibility(View.VISIBLE);
    }

    /** Hides fields a just-enqueued skeleton has no values for (UNKNOWN chips read as facts). */
    static void hideAbsentFields(@NonNull DownloadAdapter.DownloadHolder holder,
                                 @NonNull DownloadInfo info) {
        if (!SmbTaskInfo.isSmb(info)) {
            return;
        }
        if (info.uploader == null || info.uploader.isEmpty()) {
            holder.uploader.setVisibility(View.GONE);
        }
        if (info.rating <= 0f) {
            holder.rating.setVisibility(View.GONE);
        }
        if (info.category == EhUtils.UNKNOWN) {
            holder.category.setVisibility(View.GONE);
        }
    }

    /** No start/stop on another device's live task; an abandoned one keeps start = take over. */
    static void hideControlsWeCannotHonour(@NonNull DownloadAdapter.DownloadHolder holder,
                                           @NonNull DownloadInfo info) {
        if (SmbTaskInfo.isSmb(info)
                && !SmbTaskInfo.isActionable(info)
                && !SmbTaskInfo.canTakeOver(info)) {
            holder.start.setVisibility(View.GONE);
            holder.stop.setVisibility(View.GONE);
        }
    }

    /** SMB progress binding (no speed figure — nobody measures another device's rate). */
    static boolean bindProgress(@NonNull DownloadAdapter.DownloadHolder holder,
                                @NonNull DownloadInfo info) {
        if (!SmbTaskInfo.isSmb(info)) {
            return false;
        }
        holder.speed.setVisibility(View.GONE);
        hideControlsWeCannotHonour(holder, info);
        return true;
    }

    /** Start on an SMB row: resume own, confirm-adopt an orphan; never DownloadService. */
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

    /** The stop button on an SMB row, or false when this is not one. */
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
