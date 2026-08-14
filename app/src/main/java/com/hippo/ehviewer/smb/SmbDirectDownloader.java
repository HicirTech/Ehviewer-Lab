package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.image.Image;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Standalone background downloader for "Save to SMB" galleries — the conductor, not the score.
 * <p>
 * Bypasses the normal {@link com.hippo.ehviewer.download.DownloadManager} entirely so SMB-saved
 * galleries never appear in the Downloads list. Internally uses a {@link SpiderQueen} in
 * {@link SpiderQueen#MODE_DOWNLOAD} which, combined with {@code SpiderDen} routing writes through
 * the SMB layer, downloads every page directly into the SMB share.
 * <p>
 * The moving parts each live in a class of their own (#98), and this one only wires them:
 * <ul>
 *   <li>{@link SmbTaskLedger} — the queue state machine: every map, every transition, one lock.
 *       Transitions return what remains to be done; this class does it.</li>
 *   <li>{@link SmbDownloadForeground} — the foreground service handle and the words in its
 *       notification.</li>
 *   <li>{@link SmbDownloadBoard} — the download's other life, on the share (#59): heartbeat,
 *       the all-devices list, takeover, restore. It sees this device only through
 *       {@link SmbDownloadBoard.Device}, implemented here over the ledger.</li>
 * </ul>
 * What stays in this file is exactly the parts that touch more than one of those at once: the
 * public API, the pump, the SpiderQueen job lifecycle, and move-to-share's phone-copy cleanup.
 */
public final class SmbDirectDownloader {

    private static final String TAG = "SmbDirectDownloader";
    private static final int MAX_CONCURRENT = 1;

    private static final SmbDirectDownloader INSTANCE = new SmbDirectDownloader();

    public static SmbDirectDownloader getInstance() {
        return INSTANCE;
    }

    private SmbDirectDownloader() {}

    private final SmbTaskLedger ledger = new SmbTaskLedger();
    private final SmbDownloadForeground foreground = new SmbDownloadForeground();
    private final CopyOnWriteArrayList<TaskObserver> observers = new CopyOnWriteArrayList<>();

    /**
     * Written by {@link #start} / {@link #attachService} from any thread, read by main-thread
     * pump / notification updates. {@code volatile} keeps the writes visible without a full
     * lock; the field is otherwise idempotent (only flipped from null to a process-lived
     * application context).
     */
    @Nullable
    private volatile Context appContext;

    // ---------- Public API --------------------------------------------------------------------

    /**
     * Moves a gallery from phone storage to the share, as an ordinary SMB download.
     *
     * <p>It used to be its own copy loop, which meant a second way of writing a gallery to the
     * share with its own notion of when a folder is finished -- and #88 was the bill for that: the
     * folder appeared before the first byte and nothing claimed it, so every other device read it
     * as a finished gallery while it was still empty. As a download it takes the same claim in
     * {@code state/} that everything else does, and the question does not arise.
     */
    public void startMove(@NonNull Context context, @NonNull GalleryInfo info) {
        enqueue(context, info, true);
    }

    /** Enqueue a gallery for SMB save. No-ops if it is already active or queued. */
    public void start(@NonNull Context context, @NonNull GalleryInfo info) {
        enqueue(context, info, false);
    }

    private void enqueue(@NonNull Context context, @NonNull GalleryInfo info, boolean asMove) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
        if (!ledger.enqueue(info, asMove)) {
            return;
        }
        foreground.ensureStarted(appContext);
        SimpleHandler.getInstance().post(this::pumpOnMainThread);
        notifyObservers();
        // Publish as soon as the claim is made. Not a guarantee that it lands before the download
        // starts -- this hands the write to another thread while the job is posted to the main one,
        // and neither waits for the other -- so two devices deciding on the same gallery within a
        // second or so of each other can still both begin it. Closing that window entirely would
        // need the lock this design gets its speed by not taking. What this does buy is the common
        // case, and the one where nothing else would publish for a while: a gallery queued behind
        // an active job triggers no startJob, so without this its claim would wait for the next
        // heartbeat.
        publishState();
    }

    /**
     * Cancel a task by gid. Removes it from queue/paused immediately; for an active task,
     * releases the SpiderQueen on the main thread. The SMB-target mark is also cleared so a
     * subsequent download via DownloadManager (if the user chooses "to phone") would not be
     * silently re-routed to SMB.
     */
    public void cancel(long gid) {
        SimpleHandler.getInstance().post(() -> {
            SmbTaskLedger.CancelOutcome outcome = ledger.cancel(gid);
            releaseQueen(outcome.jobToRelease, "cancel", gid);
            // Wipe the on-share folder so partial pages don't accumulate. Run on the IO pool
            // because SMB delete is a network round trip. Must happen AFTER releasing the
            // SpiderQueen so we're not racing its writes.
            if (outcome.infoForDelete != null) {
                final GalleryInfo info = outcome.infoForDelete;
                IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                    try {
                        SmbGalleryLifecycle.deleteGalleryFolder(info);
                    } catch (Throwable e) {
                        Log.w(TAG, "Failed to delete SMB folder on cancel gid=" + gid, e);
                    }
                });
            }
            SmbSpiderStorage.unmarkGidAsSmbTarget(gid);
            afterQueueChange();
            foreground.stopIfIdle(ledger.isIdle(), appContext);
        });
    }

    /**
     * Pause a task. Active → release the queen but keep the gid held so the user can resume
     * later (the partially-saved pages on the share will be skipped by SpiderQueen's existence
     * check, giving "resume" semantics for free). Queued → just move to paused.
     */
    public void pause(long gid) {
        SimpleHandler.getInstance().post(() -> {
            releaseQueen(ledger.pause(gid), "pause", gid);
            afterQueueChange();
        });
    }

    /** Resume a paused task by re-enqueueing it. No-op if the task isn't paused. */
    public void resume(long gid) {
        SimpleHandler.getInstance().post(() -> {
            GalleryInfo info = ledger.takeOutPaused(gid);
            if (info == null) {
                return;
            }
            // The hypothetical this guard was written for became the normal case (#59): a paused
            // task restored from the share never goes through start() or attachService(), so
            // nothing has latched a context, and the first thing the user does to it is press
            // play. Falling back to the application rather than refusing -- there is always one,
            // and refusing made the button look broken.
            Context ctx = appContext != null ? appContext : EhApplication.getInstance();
            if (ctx == null) {
                Log.w(TAG, "resume: no context available, cannot re-enqueue gid=" + gid);
                return;
            }
            start(ctx, info);
        });
    }

    /**
     * Snapshot of every known SMB download task, ordered: active first, then queued, then paused.
     * Safe to call from any thread.
     */
    @NonNull
    public List<TaskSnapshot> snapshotTasks() {
        // Whoever is asking wants the whole queue, including whatever outlived the last process.
        ensureRestored();
        return ledger.taskSnapshots();
    }

    /** See {@link SmbDownloadBoard#ensureRestored}; kept here because every entry point has this in hand. */
    public void ensureRestored() {
        SmbDownloadBoard.getInstance().ensureRestored();
    }

    /**
     * Called when the master switch may have moved, or a screen wants the queue brought up to date.
     *
     * <p>Off means off: the downloads stop, the heartbeat stops, the service goes away and the app
     * is local-only. Back on means going and looking at the share again rather than trusting
     * whatever this process was last told — another device may have taken work over in between.
     */
    public void onSmbAvailabilityChanged() {
        boolean available = SmbDownloadBoard.smbAvailable();
        // Only on a change. Screens call this whenever they refresh, and the download list
        // refreshes on every finished page -- reconciling that often turned a rare repair into a
        // hot path, and one that races completion: it reads the published file, a job finishes
        // before it writes, and the task it thought was missing comes back as paused.
        Boolean was = lastKnownAvailable;
        if (was != null && was == available) {
            return;
        }
        lastKnownAvailable = available;
        if (!available) {
            SimpleHandler.getInstance().post(this::suspendAllOnMainThread);
            return;
        }
        SmbDownloadBoard.getInstance().scheduleReconcile();
    }

    /** Null until the first check; then whatever the switch last said. */
    @Nullable
    private volatile Boolean lastKnownAvailable;

    // ---------- Task monitor API --------------------------------------------------------------

    /** Snapshot of one SMB download task as seen by the task monitor UI. */
    public static final class TaskSnapshot {
        public enum State { ACTIVE, QUEUED, PAUSED }
        public final long gid;
        @Nullable public final String title;
        public final int finished;
        public final int total;
        @NonNull public final State state;

        TaskSnapshot(long gid, @Nullable String title, int finished, int total, @NonNull State state) {
            this.gid = gid;
            this.title = title;
            this.finished = finished;
            this.total = total;
            this.state = state;
        }
    }

    public interface TaskObserver {
        /** Posted on the main thread when the task list changes (add/remove/state). */
        void onTasksChanged();
    }

    public void addTaskObserver(@NonNull TaskObserver o) { observers.addIfAbsent(o); }

    public void removeTaskObserver(@NonNull TaskObserver o) { observers.remove(o); }

    private void notifyObservers() {
        SimpleHandler.getInstance().post(() -> {
            for (TaskObserver o : observers) {
                try { o.onTasksChanged(); } catch (Throwable ignored) {}
            }
        });
    }

    // ---------- The pump and the jobs ---------------------------------------------------------

    private void pumpOnMainThread() {
        if (appContext == null) {
            return;
        }
        GalleryInfo next;
        while ((next = ledger.nextToStart(MAX_CONCURRENT)) != null) {
            startJob(next);
        }
        updateNotification();
        foreground.stopIfIdle(ledger.isIdle(), appContext);
    }

    private void startJob(@NonNull GalleryInfo info) {
        // Make sure the gallery has its metadata.json before pages start landing beside it.
        // Enqueuing writes one, but the two ways a download can begin without ever being enqueued
        // here -- restored from the share after a restart, or adopted from a device that went away
        // -- both skipped it. The result is a folder full of images that Local Inventory will not
        // list and whose row has no cover or category. Only when absent: a finished gallery's
        // metadata carries tags this skeleton does not.
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                if (SmbInventory.readGalleryMetadata(info) == null) {
                    SmbMetadata.writeMetadataSkeleton(info);
                }
            } catch (Throwable e) {
                Log.w(TAG, "Could not ensure metadata for gid=" + info.gid, e);
            }
        });
        // Mark BEFORE obtaining the queen so the SpiderDen it constructs immediately routes
        // to SMB. Unmarked in onJobFinish.
        SmbSpiderStorage.markGidAsSmbTarget(info.gid);
        try {
            SpiderQueen queen = SpiderQueen.obtainSpiderQueen(appContext, info, SpiderQueen.MODE_DOWNLOAD);
            ListenerImpl listener = new ListenerImpl(info);
            queen.addOnSpiderListener(listener);
            ledger.jobStarted(info, new SmbTaskLedger.ActiveJob(queen, listener, info));
            Log.i(TAG, "SMB direct download started gid=" + info.gid);
            // Push an immediate notification update so the progress bar appears as soon as the
            // job starts, rather than staying on "Preparing..." until the first onPageSuccess
            // arrives (which can take many seconds for big galleries on slow SMB shares).
            updateNotification();
            notifyObservers();
            // Queued -> active. Worth its own write: another device seeing "active" knows this one
            // is really working on it, not merely intending to.
            publishState();
        } catch (IllegalStateException e) {
            // A regular DownloadManager download is already in progress for this gid.
            // We must NOT leave the gid marked or its concurrent phone download would
            // start routing through SMB mid-flight.
            SmbSpiderStorage.unmarkGidAsSmbTarget(info.gid);
            Log.w(TAG, "SMB direct download skipped for gid=" + info.gid + ": " + e.getMessage());
        } catch (Throwable e) {
            SmbSpiderStorage.unmarkGidAsSmbTarget(info.gid);
            Log.e(TAG, "Failed to start SMB direct download gid=" + info.gid, e);
        }
    }

    private void onJobFinish(@NonNull GalleryInfo info) {
        SmbTaskLedger.FinishOutcome outcome = ledger.finish(info);
        if (outcome.job == null) {
            return;
        }
        Log.i(TAG, "SMB direct download finished gid=" + info.gid);
        // Drop the claim promptly: the gallery is on the share now, and leaving it listed would
        // have other devices think it is still being worked on.
        publishState();
        // Finalize metadata + cover on the IO pool.
        final Context ctx = appContext != null ? appContext : EhApplication.getInstance();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                SmbGalleryLifecycle.finalizeDownloadedGallery(ctx, info);
            } catch (Throwable e) {
                Log.e(TAG, "SMB finalize failed for gid=" + info.gid, e);
            }
            if (outcome.wasMove) {
                dropPhoneCopy(ctx, info);
            }
        });
        // releaseSpiderQueen must run on the main thread.
        SimpleHandler.getInstance().post(() -> {
            releaseQueen(outcome.job, "finish", info.gid);
            // Keep the gid marked so any subsequent READs from LocalInventoryScene
            // continue to resolve to SMB even before the process restarts.
            pumpOnMainThread();
        });
    }

    /**
     * Drops a task another device has taken over, without touching what is on the share.
     *
     * <p>Deliberately not {@link #cancel}: that deletes the gallery folder, and the folder now
     * belongs to whoever adopted the download. The pages already written are theirs to continue
     * from -- that is what makes a takeover a resumption rather than a restart.
     */
    private void yieldOnMainThread(long gid) {
        releaseQueen(ledger.yield(gid), "yield", gid);
        SmbSpiderStorage.unmarkGidAsSmbTarget(gid);
        notifyObservers();
        updateNotification();
        SimpleHandler.getInstance().post(this::pumpOnMainThread);
        foreground.stopIfIdle(ledger.isIdle(), appContext);
    }

    /**
     * Stops everything SMB and holds it, without touching the share.
     *
     * <p>Nothing is published — the switch being off, or the share being unreachable, is exactly
     * the situation in which this device cannot say anything. Its file simply stops being
     * refreshed, and after {@link SmbDownloadStateStore#STALE_AFTER_MS} the other devices draw
     * their own conclusion, which is the correct one.
     *
     * <p>The work is held rather than cancelled: the pages already on the share stay, and the
     * tasks come back paused when SMB does.
     */
    private void suspendAllOnMainThread() {
        List<SmbTaskLedger.ActiveJob> released = ledger.suspendAll();
        for (SmbTaskLedger.ActiveJob j : released) {
            releaseQueen(j, "suspend", j.info.gid);
        }
        if (!released.isEmpty() || ledger.hasWork()) {
            Log.i(TAG, "SMB is unavailable; holding " + released.size() + " download(s)");
        }
        SmbDownloadBoard.getInstance().syncHeartbeat();
        notifyObservers();
        foreground.stopIfIdle(ledger.isIdle(), appContext);
    }

    /** Every path that lets go of a running queen goes through here; failing is survivable. */
    private void releaseQueen(@Nullable SmbTaskLedger.ActiveJob job, @NonNull String why, long gid) {
        if (job == null) {
            return;
        }
        try {
            job.queen.removeOnSpiderListener(job.listener);
            SpiderQueen.releaseSpiderQueen(job.queen, SpiderQueen.MODE_DOWNLOAD);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to release SpiderQueen on " + why + " gid=" + gid, e);
        }
    }

    /** The trio every queue mutation owes the world: redraw, notify screens, tell the share. */
    private void afterQueueChange() {
        notifyObservers();
        publishState();
        updateNotification();
        SimpleHandler.getInstance().post(this::pumpOnMainThread);
    }

    /**
     * Removes the phone's copy of a gallery that has just finished moving to the share.
     *
     * <p>Last, and only after the pages are on the share, so a move that fails part way leaves the
     * phone copy where it was rather than nothing anywhere.
     *
     * <p>The download record goes on the main thread because that is where the download list and
     * its listeners live; the files go on this one, because deleting a folder of images through
     * the storage-access framework is slow enough to be felt.
     */
    private void dropPhoneCopy(@NonNull Context appContext, @NonNull GalleryInfo info) {
        final UniFile dir = SpiderDen.getExistingGalleryDownloadDir(info);
        SimpleHandler.getInstance().post(() -> {
            try {
                EhDB.removeDownloadDirname(info.gid);
                LongList one = new LongList();
                one.add(info.gid);
                EhApplication.getDownloadManager(appContext).deleteRangeDownload(one);
            } catch (Throwable e) {
                Log.w(TAG, "Could not drop the phone download record gid=" + info.gid, e);
            }
            if (dir == null) {
                return;
            }
            IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                try {
                    dir.delete();
                } catch (Throwable e) {
                    Log.w(TAG, "Could not delete the phone copy gid=" + info.gid, e);
                }
            });
        });
    }

    // ---------- Service, notification, board --------------------------------------------------

    /** Service lifecycle hooks. Called by {@link SmbDownloadService}. */
    void attachService(@NonNull SmbDownloadService svc) {
        foreground.attach(svc);
        if (appContext == null) {
            appContext = svc.getApplicationContext();
        }
        // The service coming up is the one signal that does not depend on a screen being open --
        // including when Android restarts it after killing the process, which it does precisely
        // because there was work in flight. That work is on the share; this is what goes and gets
        // it. Idempotent, so the ordinary case of the service starting for a fresh enqueue costs
        // one no-op.
        ensureRestored();
        SimpleHandler.getInstance().post(this::pumpOnMainThread);
    }

    void detachService() {
        foreground.detach();
    }

    private void updateNotification() {
        Context ctx = appContext != null ? appContext : EhApplication.getInstance();
        foreground.update(ctx, ledger.notificationContent());
    }

    /** The board handles the share side; every structural change here tells it to say so. */
    private void publishState() {
        SmbDownloadBoard.getInstance().publish();
    }

    /**
     * This device's queue as the share should see it.
     *
     * <p>Package-private so a test can read what would have been published without a share to
     * publish to.
     */
    @NonNull
    SmbDownloadState.ClientState snapshotClientState() {
        return ledger.clientState();
    }

    // ---------- The board's window onto this device (#98) --------------------------------------

    /** One bridge per process; the board never sees the ledger, only these answers. */
    private final SmbDownloadBoard.Device deviceBridge = new SmbDownloadBoard.Device() {
        @Override
        @NonNull
        public SmbDownloadState.ClientState snapshot() {
            return ledger.clientState();
        }

        @Override
        public boolean hasWork() {
            return ledger.hasWork();
        }

        @Override
        public boolean isRetired(long gid) {
            return ledger.isRetired(gid);
        }

        @Override
        public void yieldTask(long gid) {
            SimpleHandler.getInstance().post(() -> yieldOnMainThread(gid));
        }

        @Override
        public void restore(@NonNull List<SmbDownloadState.Task> tasks) {
            SimpleHandler.getInstance().post(() -> {
                ledger.restore(tasks);
                notifyObservers();
                publishState();
            });
        }

        @Override
        public void stampAdoption(@NonNull SmbTaskInfo task) {
            ledger.stampAdoption(task);
        }

        @Override
        public void enqueueAdopted(@NonNull Context context, @NonNull GalleryInfo info) {
            SimpleHandler.getInstance().post(() -> start(context, info));
        }

        @Override
        public int localRowState(long gid) {
            return ledger.localRowState(gid);
        }
    };

    @NonNull
    SmbDownloadBoard.Device deviceBridge() {
        return deviceBridge;
    }

    // ---------- SpiderQueen callbacks ---------------------------------------------------------

    private final class ListenerImpl implements SpiderQueen.OnSpiderListener {
        private final GalleryInfo info;

        ListenerImpl(GalleryInfo info) {
            this.info = info;
        }

        @Override
        public void onGetPages(int pages) {
            ledger.updateTotal(info.gid, pages);
            updateNotification();
        }

        @Override
        public void onGet509(int index) {}

        @Override
        public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {}

        @Override
        public void onPageSuccess(int index, int finished, int downloaded, int total) {
            ledger.updateProgress(info.gid, finished, total);
            updateNotification();
            notifyObservers();
        }

        @Override
        public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
            ledger.updateProgress(info.gid, finished, total);
            updateNotification();
            notifyObservers();
        }

        @Override
        public void onFinish(int finished, int downloaded, int total) {
            onJobFinish(info);
            notifyObservers();
        }

        @Override
        public void onGetImageSuccess(int index, Image image) {}

        @Override
        public void onGetImageFailure(int index, String error) {}
    }
}
