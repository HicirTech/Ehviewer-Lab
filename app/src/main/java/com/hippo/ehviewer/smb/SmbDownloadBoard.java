package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.DownloadState;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.SimpleHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The download queue's life on the share (#59): heartbeat/publish, the merged all-devices task
 * list, the claimed-elsewhere check, takeover, restore. Talks to the device side only through
 * {@link Device}; the decisions are pure functions on {@link DownloadState}, this class reads,
 * decides via them, and applies.
 */
public final class SmbDownloadBoard {

    private static final String TAG = "SmbDirectDownloader";

    /** What the board may ask of the device side. Implemented by {@link SmbDirectDownloader}. */
    interface Device {
        /** The queue as the share should see it, now. */
        @NonNull
        DownloadState.ClientState snapshot();

        boolean hasWork();

        /** Finished this process-lifetime; a stale read must not bring it back. */
        boolean isRetired(long gid);

        /** Taken over elsewhere: drop locally, touch nothing shared. */
        void yieldTask(long gid);

        /** Recovered tasks: hold as paused, with their progress. */
        void restore(@NonNull List<DownloadState.Task> tasks);

        /** Takeover succeeded: stamp claim time and previous owner... */
        void stampAdoption(@NonNull SmbTaskInfo task);

        /** ...and enqueue the adopted gallery like any other. */
        void enqueueAdopted(@NonNull Context context, @NonNull GalleryInfo info);

        /** Own row state for this gid (active/waiting/none). */
        int localRowState(long gid);
    }

    private static volatile SmbDownloadBoard sInstance;

    @NonNull
    public static SmbDownloadBoard getInstance() {
        SmbDownloadBoard instance = sInstance;
        if (instance == null) {
            synchronized (SmbDownloadBoard.class) {
                if (sInstance == null) {
                    sInstance = new SmbDownloadBoard(SmbDirectDownloader.getInstance().deviceBridge());
                }
                instance = sInstance;
            }
        }
        return instance;
    }

    private final Device device;

    /** Package-visible so a test can put a fake device behind a board of its own. */
    SmbDownloadBoard(@NonNull Device device) {
        this.device = device;
    }

    /** The gate every SMB surface shares: master switch on and a share configured. */
    static boolean smbAvailable() {
        return Settings.getNetworkStorageEnabled() && NetworkStorage.active().isConfigured();
    }

    // ---------- Publishing to the share (#59) ----------

    /** The pulse; this class only tells it what a publish writes and when beats are wanted. */
    private final SmbHeartbeat pulse = new SmbHeartbeat(new SmbHeartbeat.Shell() {
        @Override
        public boolean publishSelf() {
            if (!smbAvailable()) {
                return false;
            }
            try {
                return NetworkStorage.active().stateStore().writeSelf(device.snapshot());
            } catch (Throwable e) {
                // Costs visibility only; the next beat carries the same state.
                Log.w(TAG, "Failed to publish download state", e);
                return false;
            }
        }

        @Override
        public boolean shouldBeat() {
            return device.hasWork() && smbAvailable();
        }

        @Override
        public void onBackFromSilence() {
            scheduleReconcile();
        }
    });

    /** Restoring is a once-per-process affair, whichever entry point asks for it first. */
    private final AtomicBoolean restoreStarted = new AtomicBoolean();

    /** Publishes the queue soon; called at every structural change (a claim must be visible). */
    public void publish() {
        pulse.publish();
    }

    /** Re-syncs whether the heartbeat should be running; the device calls this on suspend. */
    void syncHeartbeat() {
        pulse.sync();
    }

    // ---------- Reading the share back (#59) ----------

    /** Brings the queue back from the share, once per process. */
    public void ensureRestored() {
        if (!restoreStarted.compareAndSet(false, true)) {
            return;
        }
        if (!NetworkStorage.active().isConfigured()) {
            return;
        }
        scheduleReconcile();
    }

    /** Queues a reconcile on the publisher thread; safe from any thread. */
    void scheduleReconcile() {
        pulse.execute(this::reconcileWithShare);
    }

    /** Makes queue and share agree both ways; the plan itself is pure (planReconcile). */
    private void reconcileWithShare() {
        final DownloadState.ReconcilePlan plan;
        try {
            plan = DownloadState.planReconcile(
                    Settings.getSmbClientId(),
                    device.snapshot(),
                    NetworkStorage.active().stateStore().readAll(),
                    device::isRetired);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to reconcile with the share", e);
            return;
        }
        for (long gid : plan.yields) {
            Log.i(TAG, "gid=" + gid + " was taken over elsewhere; standing down");
            device.yieldTask(gid);
        }
        if (plan.shouldPublish) {
            // Our file must stop advertising claims we no longer hold.
            publish();
        }
        if (!plan.restores.isEmpty()) {
            device.restore(plan.restores);
        }
    }

    // ---------- The all-devices view ----------

    /** Every device's downloads as one merged list. SMB I/O; worker thread. */
    @NonNull
    public List<SmbTaskInfo> snapshotSharedTasks() {
        if (!NetworkStorage.active().isConfigured()) {
            return new ArrayList<>();
        }
        try {
            String selfId = Settings.getSmbClientId();
            List<DownloadState.Published> all = new ArrayList<>();
            for (DownloadState.Published p : NetworkStorage.active().stateStore().readAll()) {
                if (!p.state.clientId.equals(selfId)) {
                    all.add(p);
                }
            }
            // Own rows come from the live queue, not the published file, which lags every action.
            all.add(new DownloadState.Published(
                    device.snapshot(), true, System.currentTimeMillis()));
            List<DownloadState.OwnedTask> merged = DownloadState.merge(all);
            List<SmbTaskInfo> out = new ArrayList<>(merged.size());
            for (DownloadState.OwnedTask o : merged) {
                out.add(SmbTaskInfo.of(o, selfId, galleryMetadata(o.task), rowStateOf(o, selfId)));
            }
            return out;
        } catch (Throwable e) {
            // Unreachable share: an empty list is the honest answer.
            Log.w(TAG, "Could not read the shared task list", e);
            return new ArrayList<>();
        }
    }

    // Row extras come from the gallery's own metadata.json (one authoritative record). Cached
    // per gid; misses are not cached (the skeleton may land any moment).
    private final Map<Long, GalleryInfo> metadataCache =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Long, GalleryInfo>(
                    16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, GalleryInfo> eldest) {
                    // Far above any real task list; an evicted row just re-reads its metadata.
                    return size() > 128;
                }
            });

    @Nullable
    private GalleryInfo galleryMetadata(@NonNull DownloadState.Task task) {
        GalleryInfo cached = metadataCache.get(task.gid);
        if (cached != null) {
            return cached;
        }
        GalleryInfo hint = new GalleryInfo();
        hint.gid = task.gid;
        hint.title = task.title;
        GalleryInfo read = NetworkStorage.active().inventory().readGalleryMetadata(hint);
        if (read != null) {
            metadataCache.put(task.gid, read);
        }
        return read;
    }

    /** Own rows answer from this process; others' only from liveness (mtime, not contents). */
    private int rowStateOf(@NonNull DownloadState.OwnedTask owned, @NonNull String selfId) {
        if (!owned.ownerAlive) {
            return com.hippo.ehviewer.dao.DownloadInfo.STATE_FAILED;   // drawn as "device offline"
        }
        if (!owned.clientId.equals(selfId)) {
            return com.hippo.ehviewer.dao.DownloadInfo.STATE_DOWNLOAD;
        }
        return device.localRowState(owned.task.gid);
    }

    /** Whether a live rival already claims this gid. SMB I/O; worker thread. */
    public boolean isClaimedElsewhere(long gid) {
        if (!NetworkStorage.active().isConfigured()) {
            return false;
        }
        try {
            return DownloadState.isClaimedByAnotherLiveClient(
                    DownloadState.merge(NetworkStorage.active().stateStore().readAll()),
                    gid, Settings.getSmbClientId());
        } catch (Throwable e) {
            // On doubt, proceed: a duplicate download wastes bandwidth, a refusal loses the gallery.
            Log.w(TAG, "Could not check whether gid=" + gid + " is claimed elsewhere", e);
            return false;
        }
    }

    // ---------- Takeover ----------

    /** How a takeover attempt ended, so the caller can say something useful about it. */
    public enum TakeOverResult {
        /** Adopted; it is in this device's queue now. */
        TAKEN,
        /** The owner turned out to be alive after all, so it was left alone. */
        OWNER_RETURNED,
        /** The share could not be read, or the claim could not be published. */
        FAILED
    }

    public interface TakeOverCallback {
        void onTakeOverFinished(@NonNull TakeOverResult result);
    }

    /**
     * Adopts an orphaned download: fresh liveness re-check, claim stamped now (later claim wins
     * the merge), owner's stale entry cleared. Async; result posted to the main thread.
     */
    public void takeOver(@NonNull Context context, @NonNull SmbTaskInfo task,
                         @NonNull TakeOverCallback onResult) {
        final Context appContext = context.getApplicationContext();
        pulse.execute(() -> {
            TakeOverResult result = takeOverNow(appContext, task);
            SimpleHandler.getInstance().post(() -> onResult.onTakeOverFinished(result));
        });
    }

    @NonNull
    private TakeOverResult takeOverNow(@NonNull Context ctx, @NonNull SmbTaskInfo task) {
        final DownloadState.TakeOverAssessment fresh;
        try {
            fresh = DownloadState.assessTakeOver(
                    DownloadState.merge(NetworkStorage.active().stateStore().readAll()),
                    task.gid, Settings.getSmbClientId());
        } catch (Throwable e) {
            // No fresh read = no adoption; two devices running one download is the worse outcome.
            Log.w(TAG, "Could not confirm gid=" + task.gid + " is still orphaned", e);
            return TakeOverResult.FAILED;
        }
        switch (fresh) {
            case ALREADY_OURS:
                return TakeOverResult.TAKEN;   // already ours, by whatever route
            case OWNER_ALIVE:
                return TakeOverResult.OWNER_RETURNED;
            case ORPHAN:
            default:
                break;
        }

        GalleryInfo info = new GalleryInfo();
        info.gid = task.gid;
        info.token = task.token;
        info.title = task.title;
        info.pages = task.total;
        device.stampAdoption(task);
        // The one write ever made to another device's file, and only to one silent past
        // STALE_AFTER_MS; leaving the stale entry would resurface it after we finish.
        if (!NetworkStorage.active().stateStore().removeTask(task.ownerClientId, task.gid)) {
            // Not fatal: our live, newer claim wins the merge anyway.
            Log.w(TAG, "Took over gid=" + task.gid + " but could not clear it from "
                    + task.ownerClientId);
        }
        device.enqueueAdopted(ctx, info);
        return TakeOverResult.TAKEN;
    }
}
