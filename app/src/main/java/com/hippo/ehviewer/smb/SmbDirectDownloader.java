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
import com.hippo.ehviewer.storage.DownloadState;
import com.hippo.ehviewer.storage.GalleryTargets;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.image.Image;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.unifile.UniFile;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Background downloader for "Save to SMB" galleries: SpiderQueen in MODE_DOWNLOAD writing
 * straight to the share, bypassing DownloadManager. This class is the conductor over
 * {@link SmbTaskLedger} (queue state), {@link SmbDownloadForeground} (service/notification) and
 * {@link SmbDownloadBoard} (the share side, #59) — it keeps only the API, the pump, the job
 * lifecycle and the move cleanup.
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

    /** Latched once from any entry point; only ever flips null → application context. */
    @Nullable
    private volatile Context appContext;

    // ---------- Public API --------------------------------------------------------------------

    /** Move = an ordinary download that also drops the phone copy; a separate loop was #88. */
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
        // Publish the claim promptly; a small two-devices race window remains by design.
        publishState();
    }

    /** Cancels: forget locally, wipe the on-share folder, clear the SMB-target mark. */
    public void cancel(long gid) {
        SimpleHandler.getInstance().post(() -> {
            SmbTaskLedger.CancelOutcome outcome = ledger.cancel(gid);
            releaseQueen(outcome.jobToRelease, "cancel", gid);
            // Delete AFTER the queen is released, on the IO pool — not racing its writes.
            if (outcome.infoForDelete != null) {
                final GalleryInfo info = outcome.infoForDelete;
                IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                    try {
                        NetworkStorage.active().lifecycle().deleteGalleryFolder(info);
                    } catch (Throwable e) {
                        Log.w(TAG, "Failed to delete SMB folder on cancel gid=" + gid, e);
                    }
                });
            }
            GalleryTargets.unmark(gid);
            afterQueueChange();
            foreground.stopIfIdle(ledger.isIdle(), appContext);
        });
    }

    /** Pauses; resume comes free (SpiderQueen skips pages already on the share). */
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
            // Restored tasks never latched a context; fall back rather than break the button (#59).
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

    /** Off = suspend everything; back on = reconcile with the share before trusting memory. */
    public void onSmbAvailabilityChanged() {
        boolean available = SmbDownloadBoard.smbAvailable();
        // Only on a change: screens call this per refresh, and reconciling races completion.
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
        // Restored/adopted downloads skipped the enqueue-time metadata skeleton; write it if
        // absent, or the inventory will not list the folder.
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                if (NetworkStorage.active().inventory().readGalleryMetadata(info) == null) {
                    NetworkStorage.active().metadata().writeMetadataSkeleton(info);
                }
            } catch (Throwable e) {
                Log.w(TAG, "Could not ensure metadata for gid=" + info.gid, e);
            }
        });
        // Mark BEFORE obtaining the queen so the SpiderDen it constructs immediately routes
        // to SMB. Unmarked in onJobFinish.
        GalleryTargets.mark(info.gid);
        try {
            SpiderQueen queen = SpiderQueen.obtainSpiderQueen(appContext, info, SpiderQueen.MODE_DOWNLOAD);
            ListenerImpl listener = new ListenerImpl(info);
            queen.addOnSpiderListener(listener);
            ledger.jobStarted(info, new SmbTaskLedger.ActiveJob(queen, listener, info));
            Log.i(TAG, "SMB direct download started gid=" + info.gid);
            updateNotification();
            notifyObservers();
            // Queued -> active is worth its own write: "active" means working, not intending.
            publishState();
        } catch (IllegalStateException e) {
            // A phone download already runs this gid; leaving the mark would re-route it mid-flight.
            GalleryTargets.unmark(info.gid);
            Log.w(TAG, "SMB direct download skipped for gid=" + info.gid + ": " + e.getMessage());
        } catch (Throwable e) {
            GalleryTargets.unmark(info.gid);
            Log.e(TAG, "Failed to start SMB direct download gid=" + info.gid, e);
        }
    }

    private void onJobFinish(@NonNull GalleryInfo info) {
        SmbTaskLedger.FinishOutcome outcome = ledger.finish(info);
        if (outcome.job == null) {
            return;
        }
        Log.i(TAG, "SMB direct download finished gid=" + info.gid);
        // Drop the claim promptly, or other devices think this is still being worked on.
        publishState();
        final Context ctx = appContext != null ? appContext : EhApplication.getInstance();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                NetworkStorage.active().lifecycle().finalizeDownloadedGallery(ctx, info);
            } catch (Throwable e) {
                Log.e(TAG, "SMB finalize failed for gid=" + info.gid, e);
            }
            if (outcome.wasMove) {
                dropPhoneCopy(ctx, info);
            }
        });
        // releaseSpiderQueen must run on the main thread; the gid stays marked so reads still
        // resolve to SMB.
        SimpleHandler.getInstance().post(() -> {
            releaseQueen(outcome.job, "finish", info.gid);
            pumpOnMainThread();
        });
    }

    /** Stands down from a taken-over task; not cancel — the folder is the adopter's now. */
    private void yieldOnMainThread(long gid) {
        releaseQueen(ledger.yield(gid), "yield", gid);
        GalleryTargets.unmark(gid);
        notifyObservers();
        updateNotification();
        SimpleHandler.getInstance().post(this::pumpOnMainThread);
        foreground.stopIfIdle(ledger.isIdle(), appContext);
    }

    /** Holds everything without publishing (we cannot say anything); tasks return paused. */
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

    /** Drops the phone copy after a move — last, so a failed move fails toward "copied". */
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
        // The service coming up is the one screen-independent restore signal (Android restarts
        // it after killing a process with work in flight).
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

    /** Package-private so tests read what would have been published. */
    @NonNull
    DownloadState.ClientState snapshotClientState() {
        return ledger.clientState();
    }

    // ---------- The board's window onto this device (#98) --------------------------------------

    /** One bridge per process; the board never sees the ledger, only these answers. */
    private final SmbDownloadBoard.Device deviceBridge = new SmbDownloadBoard.Device() {
        @Override
        @NonNull
        public DownloadState.ClientState snapshot() {
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
        public void restore(@NonNull List<DownloadState.Task> tasks) {
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
