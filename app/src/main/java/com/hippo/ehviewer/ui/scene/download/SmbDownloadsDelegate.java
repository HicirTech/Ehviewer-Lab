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
import com.hippo.ehviewer.smb.SmbConnection;
import com.hippo.ehviewer.smb.SmbTaskInfo;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the Downloads screen does about SMB saves (#59), in one fork-owned class.
 *
 * <p>This used to be ~250 lines living inside upstream's {@code DownloadsScene}, and every
 * upstream merge paid for the lodging (#95). The scene now owns only what cannot leave it —
 * adapter-position bookkeeping, the selection loop, its lifecycle calls — and everything with
 * actual SMB behaviour in it lives here: the shared task list and its rate-limited refresh, the
 * take-over conversation, the long-press menu, and handing a local download to the share as a
 * move.
 *
 * <p>The screen talks to this class; this class talks to {@link SmbDirectDownloader}. The scene
 * never touches the SMB layer directly, which is also the seam #100 will want when the backend
 * stops being spelled "SMB".
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

    /**
     * How often the share may be re-read, however often something asks.
     *
     * <p>Two seconds because the thing being watched is a page count. The heartbeat that publishes
     * it only runs every twenty, so nothing finer is even visible.
     */
    private static final long REFRESH_INTERVAL_MS = 2_000L;

    private final Host mHost;

    /**
     * The SMB saves every device has published, as of the last read (#59).
     *
     * <p>Held apart from the scene's list until {@link #mergeInto} folds them in: they come from
     * the share rather than the database, and arrive on their own schedule. Empty until the first
     * read lands, so the local downloads are never kept waiting on a NAS that may not answer.
     */
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
     * Re-reads the shared list, at most every {@link #REFRESH_INTERVAL_MS}.
     *
     * <p>The downloader announces every finished page, and each announcement used to mean a full
     * pass: enumerate {@code state/}, read every device's file, rebuild the list, redraw it. A
     * hundred-page gallery did that a hundred times. Worse than the round trips was the redraw —
     * a list rebuilding under a finger loses the gesture, so with a download running a long press
     * on any row would sometimes simply not happen.
     *
     * <p>Rate-limited rather than dropped, with the last call in a burst always honoured: a page
     * count that stopped a beat early would stay wrong until something else happened to ask.
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
        final boolean enabled = Settings.getSmbSaveEnabled() && SmbConnection.isConfigured();
        if (!enabled) {
            // Not just a list that stops showing them: the downloads stop too, or the feature
            // would be off everywhere except where it counts.
            SmbDirectDownloader.getInstance().onSmbAvailabilityChanged();
            SimpleHandler.getInstance().post(() -> {
                if (!mTasks.isEmpty()) {
                    mTasks = new ArrayList<>();
                    mHost.onTasksChanged();
                }
            });
            return;
        }
        // The queue lives on the share and so outlives the process, but nothing brings it back on
        // its own. The screen that used to ask for it is gone, and this is the one that replaced
        // it -- without this an interrupted download would sit on the share forever.
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

    /**
     * Folds the shared tasks into the scene's list, first, because they are the ones in flight.
     * Only in the default view — a label is a database column and these have no row.
     */
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

    /**
     * "Stop all" means all of them, including the ones on the share that this device is
     * responsible for. Other devices' downloads are theirs to stop.
     */
    public void pauseAllOwn() {
        for (SmbTaskInfo t : mTasks) {
            if (SmbTaskInfo.isActionable(t)) {
                SmbDirectDownloader.getInstance().pause(t.gid);
            }
        }
    }

    /**
     * Asks before adopting a download whose owner has gone quiet (#59).
     *
     * <p>Worth a question rather than just a tap: the row looks like any other stalled download,
     * but starting it moves a gallery from one device's queue into this one's, and the name of the
     * device it is being taken from is the piece of information that makes that clear.
     */
    public void confirmTakeOver(@NonNull SmbTaskInfo task) {
        Context context = mHost.context();
        if (context == null) {
            return;
        }
        String title = task.title != null ? task.title : String.valueOf(task.gid);
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

    /**
     * The long-press menu for an SMB row, which cannot be multi-selected (#59).
     *
     * <p>What a long press does everywhere else on this screen is start a selection, and these are
     * kept out of selections — so the gesture is free, and it is where the actions that have
     * nowhere else to live now go. Cancelling in particular: the row's own buttons pause and
     * resume, and without this there was no way at all to take a gallery back out of the queue.
     *
     * <p>Nothing is offered for another device's live download. There is nothing this device may
     * do to it, and a menu listing only greyed-out choices is worse than no menu.
     */
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

    /**
     * Deletes an SMB save, if that is what this is, and says so. There is no database row to
     * remove and no directory on the phone: cancelling releases the claim and wipes whatever was
     * written to the share, which is everything the save consists of. The answer doubles as
     * "nothing local left to clean up" for the caller.
     */
    public boolean deleteIfSmbTask(@Nullable com.hippo.ehviewer.client.data.GalleryInfo info) {
        if (!(info instanceof SmbTaskInfo)) {
            return false;
        }
        if (SmbTaskInfo.isActionable((DownloadInfo) info)) {
            SmbDirectDownloader.getInstance().cancel(info.gid);
        }
        return true;
    }

    /**
     * Hands the selected downloads to the SMB downloader as moves.
     *
     * <p>This used to be a copy loop of its own, walking each gallery's files onto the share and
     * then deleting the phone copy. As an enqueue it inherits everything the download path already
     * has -- a claim in {@code state/} so no other device mistakes a half-copied folder for a
     * finished gallery (#88), a row in this very list with progress, pause and resume, and the
     * ability to carry on after an interruption. The pages themselves still come from the phone:
     * the download asks {@code SpiderDen.contain} for each one, and a page already in phone storage
     * is put on the share rather than fetched again.
     *
     * <p>What it no longer does is report the outcome here. A move is now as long-running as a
     * download, and the download list is where a download's progress is.
     */
    public void moveToShare(@NonNull Context context, @NonNull List<DownloadInfo> downloads) {
        final Context appContext = context.getApplicationContext();
        for (DownloadInfo info : downloads) {
            SmbDirectDownloader.getInstance().startMove(appContext, info);
        }
        Toast.makeText(appContext, R.string.download_moving_to_smb, Toast.LENGTH_SHORT).show();
    }
}
