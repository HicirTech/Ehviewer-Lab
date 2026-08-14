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

/**
 * What an SMB save's row shows and does on the Downloads screen (#59, #95).
 *
 * <p>The adapter binds rows; this class holds the SMB exceptions to that binding, so that the
 * upstream adapter is left with one-line call sites instead of paragraphs of fork behaviour.
 * Everything here is a static function of the row and the task — the state lives in
 * {@link SmbTaskInfo} and the downloader, never in the binder.
 */
final class SmbTaskRowBinder {

    private SmbTaskRowBinder() {}

    /**
     * Says which device is saving an SMB task, and when it last checked in (#59).
     *
     * <p>The "when" is the part that decides things. Another device's row cannot say whether its
     * download is moving — this one is not watching it, only reading what it last wrote — so how
     * long ago it wrote is the whole signal: seconds means it is working, and minutes means
     * something happened to it and the download is there to be taken over.
     *
     * <p>Nothing is said about this device's own tasks. The answer would be "this one, just now"
     * on every row, which is noise.
     */
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

    /**
     * Blanks the parts of the card an SMB task has nothing to put in (#59).
     *
     * <p>Most of what a row draws is read from the gallery's own {@code metadata.json} on the
     * share, so these are usually filled like any other download's. What is missing is missing for
     * a reason: a gallery only just enqueued has a skeleton and no more, and a black <i>UNKNOWN</i>
     * chip from category zero reads as a fact about the gallery rather than an absence of one.
     */
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

    /**
     * Takes away the start/stop buttons on a download belonging to another device that is still
     * running it (#59).
     *
     * <p>Neither would do anything: the download is being driven by a process on a different
     * device, and this one cannot reach into it. A button that silently does nothing is worse than
     * no button — it reads as the app having ignored the tap. An abandoned one keeps its start
     * button, because there it means "take this over".
     */
    static void hideControlsWeCannotHonour(@NonNull DownloadAdapter.DownloadHolder holder,
                                           @NonNull DownloadInfo info) {
        if (SmbTaskInfo.isSmb(info)
                && !SmbTaskInfo.isActionable(info)
                && !SmbTaskInfo.canTakeOver(info)) {
            holder.start.setVisibility(View.GONE);
            holder.stop.setVisibility(View.GONE);
        }
    }

    /**
     * The SMB part of progress binding, or false when this is not an SMB row.
     *
     * <p>Nobody measures the rate of a download happening on another device -- it is not ours to
     * watch, and publishing a figure per second would mean writing to the share that often (#59).
     * Showing "0 B/S" would read as stalled, so it is simply left out; the page count beside it
     * already says whether anything is moving.
     */
    static boolean bindProgress(@NonNull DownloadAdapter.DownloadHolder holder,
                                @NonNull DownloadInfo info) {
        if (!SmbTaskInfo.isSmb(info)) {
            return false;
        }
        holder.speed.setVisibility(View.GONE);
        hideControlsWeCannotHonour(holder, info);
        return true;
    }

    /**
     * The start button on an SMB row, or false when this is not one (#59).
     *
     * <p>An SMB save is not the phone's download service's business: it belongs to whichever
     * device claimed it, and starting it through {@code DownloadService} would fetch the same
     * gallery twice, to two different places. Starting an abandoned one means adopting it, which
     * is enough of a change of ownership to ask about first.
     */
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
