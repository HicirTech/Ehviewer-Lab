/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.ui.scene.download;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.smb.SmbDownloadBoard;
import com.hippo.ehviewer.smb.SmbTaskInfo;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the Downloads screen does about SMB saves (#59, #95): the shared task list and its
 * rate-limited refresh, takeover, the long-press menu, move-to-share. The scene never touches
 * the SMB layer directly.
 */
public final class SmbDownloadsDelegate {

    /** What the delegate needs from its screen, and nothing more. */
    public interface Host {
        /** The scene's context, or null when detached mid-callback. */
        @Nullable
        Context context();

        /** The merged list is stale — rebuild and redraw (the scene's {@code updateForLabel}). */
        void onTasksChanged();
    }

    // 2s: the watched value is published every 20s, so nothing finer is even visible.
    private static final long REFRESH_INTERVAL_MS = 2_000L;

    private final Host mHost;

    /** Every device's published saves as of the last read; empty until the first read lands. */
    @NonNull
    private volatile List<SmbTaskInfo> mTasks = new ArrayList<>();

    /** Our own downloader says when its queue changes; other devices' changes arrive on the next read. */
    private final SmbDirectDownloader.TaskObserver mObserver = this::refresh;

    private long mLastRefreshAt;
    private boolean mRefreshScheduled;

    public SmbDownloadsDelegate(@NonNull Host host) {
        mHost = host;
    }

    /** Call from the scene's {@code onCreate}: starts observing and takes the first read. */
    public void attach() {
        SmbDirectDownloader.getInstance().addTaskObserver(mObserver);
        refresh();
    }

    /** Call from the scene's {@code onDestroy}. */
    public void detach() {
        SmbDirectDownloader.getInstance().removeTaskObserver(mObserver);
    }

    /**
     * Re-reads the shared list, rate-limited (a full pass per finished page lost long-presses
     * under the redraw); the last call in a burst is always honoured.
     */
    public void refresh() {
        long now = System.currentTimeMillis();
        long since = now - mLastRefreshAt;
        if (since < REFRESH_INTERVAL_MS) {
            if (!mRefreshScheduled) {
                mRefreshScheduled = true;
                SimpleHandler.getInstance().postDelayed(() -> {
                    mRefreshScheduled = false;
                    refresh();
                }, REFRESH_INTERVAL_MS - since);
            }
            return;
        }
        mLastRefreshAt = now;
        refreshNow();
    }

    private void refreshNow() {
        final boolean enabled = Settings.getNetworkStorageEnabled() && NetworkStorage.active().isConfigured();
        if (!enabled) {
            // Off means the downloads stop too, not just the list.
            SmbDirectDownloader.getInstance().onSmbAvailabilityChanged();
            SimpleHandler.getInstance().post(() -> {
                if (!mTasks.isEmpty()) {
                    mTasks = new ArrayList<>();
                    mHost.onTasksChanged();
                }
            });
            return;
        }
        // This screen is what brings the on-share queue back after a restart.
        SmbDirectDownloader.getInstance().onSmbAvailabilityChanged();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            final List<SmbTaskInfo> fresh =
                    SmbDownloadBoard.getInstance().snapshotSharedTasks();
            SimpleHandler.getInstance().post(() -> {
                mTasks = fresh;
                mHost.onTasksChanged();
            });
        });
    }

    /** Folds shared tasks in first; only in the default view (labels are database columns). */
    @Nullable
    public List<DownloadInfo> mergeInto(@Nullable String label, @Nullable List<DownloadInfo> list) {
        List<SmbTaskInfo> smb = mTasks;
        if (label != null || smb.isEmpty() || list == null) {
            return list;
        }
        List<DownloadInfo> combined = new ArrayList<>(smb.size() + list.size());
        combined.addAll(smb);
        combined.addAll(list);
        return combined;
    }

    /** "Stop all" includes this device's share tasks; other devices' are theirs. */
    public void pauseAllOwn() {
        for (SmbTaskInfo t : mTasks) {
            if (SmbTaskInfo.isActionable(t)) {
                SmbDirectDownloader.getInstance().pause(t.gid);
            }
        }
    }

    /** Confirms a takeover, naming the device it is taken from (#59). */
    public void confirmTakeOver(@NonNull SmbTaskInfo task) {
        Context context = mHost.context();
        if (context == null) {
            return;
        }
        String title = task.title != null ? task.title : String.valueOf(task.gid);
        com.hippo.ehviewer.ui.NotificationPermission.onDownloadStart(context);
        new AlertDialog.Builder(context)
                .setTitle(R.string.smb_take_over_title)
                .setMessage(context.getString(R.string.smb_take_over_message,
                        task.deviceName, title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.smb_take_over_confirm, (dialog, which) ->
                        SmbDownloadBoard.getInstance()
                                .takeOver(context, task, this::onTakeOverFinished))
                .show();
    }

    private void onTakeOverFinished(@NonNull SmbDownloadBoard.TakeOverResult result) {
        Context context = mHost.context();
        if (context == null) {
            return;
        }
        switch (result) {
            case TAKEN:
                // The list still shows the old owner until the next read lands.
                refresh();
                break;
            case OWNER_RETURNED:
                Toast.makeText(context, R.string.smb_take_over_owner_returned,
                        Toast.LENGTH_SHORT).show();
                refresh();
                break;
            case FAILED:
            default:
                Toast.makeText(context, R.string.smb_take_over_failed, Toast.LENGTH_SHORT).show();
                break;
        }
    }

    /** Long-press menu for an SMB row (its cancel lives here); none for another device's live task. */
    public void showTaskMenu(@NonNull DownloadInfo info) {
        Context context = mHost.context();
        if (context == null || !(info instanceof SmbTaskInfo)) {
            return;
        }
        final SmbTaskInfo task = (SmbTaskInfo) info;
        final String title = task.title != null ? task.title : String.valueOf(task.gid);

        if (SmbTaskInfo.canTakeOver(task)) {
            confirmTakeOver(task);
            return;
        }
        if (!SmbTaskInfo.isActionable(task)) {
            return;
        }
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setItems(new CharSequence[]{context.getString(R.string.smb_task_action_cancel)},
                        (dialog, which) -> new AlertDialog.Builder(context)
                                .setTitle(R.string.download_remove_dialog_title)
                                .setMessage(context.getString(R.string.smb_task_cancel_message,
                                        title))
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(android.R.string.ok, (d, w) -> {
                                    SmbDirectDownloader.getInstance().cancel(task.gid);
                                    refresh();
                                })
                                .show())
                .show();
    }

    /** Deletes an SMB save (cancel = release claim + wipe share folder); true = nothing local. */
    public boolean deleteIfSmbTask(@Nullable com.hippo.ehviewer.client.data.GalleryInfo info) {
        if (!(info instanceof SmbTaskInfo)) {
            return false;
        }
        if (SmbTaskInfo.isActionable((DownloadInfo) info)) {
            SmbDirectDownloader.getInstance().cancel(info.gid);
        }
        return true;
    }

    /** Moves = enqueues (#88): claims, progress rows, resume — the pages come from the phone. */
    public void moveToShare(@NonNull Context context, @NonNull List<DownloadInfo> downloads) {
        com.hippo.ehviewer.ui.NotificationPermission.onDownloadStart(context);
        final Context appContext = context.getApplicationContext();
        for (DownloadInfo info : downloads) {
            SmbDirectDownloader.getInstance().startMove(appContext, info);
        }
        Toast.makeText(appContext, appContext.getString(R.string.download_moving_to_smb,
                NetworkStorage.active().displayName()), Toast.LENGTH_SHORT).show();
    }
}
