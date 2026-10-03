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

public final class SmbDownloadsDelegate {

    public interface Host {
        @Nullable
        Context context();

        /** The merged list is stale: rebuild and redraw. */
        void onTasksChanged();
    }

    // The watched value is published every 20s, so nothing finer is even visible.
    private static final long REFRESH_INTERVAL_MS = 2_000L;

    private final Host mHost;

    /** Every device's published saves, as of the last read. */
    @NonNull
    private volatile List<SmbTaskInfo> mTasks = new ArrayList<>();

    private final SmbDirectDownloader.TaskObserver mObserver = this::refresh;

    private long mLastRefreshAt;
    private boolean mRefreshScheduled;

    public SmbDownloadsDelegate(@NonNull Host host) {
        mHost = host;
    }

    /** Call from the scene's {@code onCreate}. */
    public void attach() {
        SmbDirectDownloader.getInstance().addTaskObserver(mObserver);
        refresh();
    }

    /** Call from the scene's {@code onDestroy}. */
    public void detach() {
        SmbDirectDownloader.getInstance().removeTaskObserver(mObserver);
    }

    /** Rate-limited, or redraws swallow long-presses; the last call of a burst still runs. */
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

    /** Default view only: labels are database columns, which shared tasks lack. */
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

    public void pauseAllOwn() {
        for (SmbTaskInfo t : mTasks) {
            if (SmbTaskInfo.isActionable(t)) {
                SmbDirectDownloader.getInstance().pause(t.gid);
            }
        }
    }

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

    @Nullable
    public String moveTargetLabel(@NonNull Context context) {
        if (!Settings.getNetworkStorageEnabled() || !NetworkStorage.active().isConfigured()) {
            return null;
        }
        return context.getString(R.string.download_move_to_smb, NetworkStorage.active().displayName());
    }

    public void moveToShare(@NonNull Context context, @NonNull List<DownloadInfo> downloads) {
        final Context appContext = context.getApplicationContext();
        List<DownloadInfo> movable = new ArrayList<>(downloads.size());
        for (DownloadInfo info : downloads) {
            if (!SmbDirectDownloader.isLocalImport(info)) {
                movable.add(info);
            }
        }
        if (movable.size() < downloads.size()) {
            Toast.makeText(appContext, appContext.getString(R.string.download_move_to_smb_local_kept,
                    NetworkStorage.active().displayName()), Toast.LENGTH_SHORT).show();
        }
        if (movable.isEmpty()) {
            return;
        }
        com.hippo.ehviewer.ui.NotificationPermission.onDownloadStart(context);
        for (DownloadInfo info : movable) {
            SmbDirectDownloader.getInstance().startMove(appContext, info);
        }
        Toast.makeText(appContext, appContext.getString(R.string.download_moving_to_smb,
                NetworkStorage.active().displayName()), Toast.LENGTH_SHORT).show();
    }
}
