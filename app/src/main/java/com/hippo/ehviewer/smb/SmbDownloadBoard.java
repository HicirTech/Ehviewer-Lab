package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
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
 * What the share knows about everyone's downloads, and how this device keeps it true (#59, #98).
 *
 * <p>The download queue has two lives. One is in this process — {@link SmbDirectDownloader}'s
 * maps, the SpiderQueen, the foreground service. The other is on the share, under {@code state/},
 * where every device posts what it holds and reads what the others do. This class is the second
 * life, whole: the heartbeat and the publish, the merged all-devices task list, the
 * claimed-elsewhere check, adopting an orphan, and bringing this device's own queue back after a
 * process death or a silence. {@link SmbDownloadState} stays the vocabulary (pure) and
 * {@link SmbDownloadStateStore} the file IO; this is the behaviour composed from them.
 *
 * <p>It talks to the device side only through {@link Device} — a handful of questions, no
 * reaching into queue maps — which is what makes the two lives separately testable, and is the
 * seam #100 would lift into a capability interface (a backend with no second client has no board
 * at all).
 *
 * <p>The class itself is an imperative shell and not much more: <em>when</em> to publish is
 * {@link SmbHeartbeat}'s, and the reconcile/takeover arithmetic is pure functions on
 * {@link SmbDownloadState} ({@code planReconcile}, {@code assessTakeOver}). What remains here is
 * reading, applying decisions, and answering the screens.
 */
public final class SmbDownloadBoard {

    private static final String TAG = "SmbDirectDownloader";

    /** What the board may ask of the device side. Implemented by {@link SmbDirectDownloader}. */
    interface Device {
        /** This device's queue as the share should see it, now. */
        @NonNull
        SmbDownloadState.ClientState snapshot();

        /** Whether there is anything worth heartbeating about. */
        boolean hasWork();

        /** Finished with this gid this process-lifetime; a stale read must not bring it back. */
        boolean isRetired(long gid);

        /** Another device took this task over: drop it locally, silently, touching nothing shared. */
        void yieldTask(long gid);

        /** Tasks recovered from the share: hold them as paused, with their progress. */
        void restore(@NonNull List<SmbDownloadState.Task> tasks);

        /** A takeover succeeded: stamp the adoption (claim time, previous owner). */
        void stampAdoption(@NonNull SmbTaskInfo task);

        /** ...and enqueue the adopted gallery like any other. */
        void enqueueAdopted(@NonNull Context context, @NonNull GalleryInfo info);

        /** How this device would draw its own row for this gid (active/waiting/none). */
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

    /**
     * Whether SMB is a thing this app is doing at all right now.
     *
     * <p>The same pair every other SMB surface gates on — Local Inventory, the save option on a
     * gallery, the drawer entry, the download list. The downloader was the one place that never
     * asked, so turning the feature off hid the tasks from the list while their pages carried on
     * being written to the share.
     */
    static boolean smbAvailable() {
        return Settings.getSmbSaveEnabled() && SmbConnection.isConfigured();
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
                return SmbDownloadStateStore.writeSelf(device.snapshot());
            } catch (Throwable e) {
                // Failing to publish costs visibility to other devices, nothing local. The next
                // beat carries the same state, so there is nothing to recover here.
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

    /**
     * Writes this device's queue to the share, and starts or stops the heartbeat to match whether
     * there is anything left to beat about.
     *
     * <p>Called at every structural change — something queued, started, paused, resumed, cancelled
     * or finished — because those are what another device needs to see promptly. An enqueue in
     * particular has to land before the download does, since a claim nobody can see is a claim that
     * does not prevent anyone downloading the same gallery twice.
     */
    public void publish() {
        pulse.publish();
    }

    /** Re-syncs whether the heartbeat should be running; the device calls this on suspend. */
    void syncHeartbeat() {
        pulse.sync();
    }

    // ---------- Reading the share back (#59) ----------

    /**
     * Restores this device's queue from the share, once per process.
     *
     * <p>The queue outlives the process because it lives on the share, but nothing brings it back
     * on its own — so the entry points that would want it ask for it, and it happens in the
     * background with observers notified when it lands.
     */
    public void ensureRestored() {
        if (!restoreStarted.compareAndSet(false, true)) {
            return;
        }
        if (!SmbConnection.isConfigured()) {
            return;
        }
        scheduleReconcile();
    }

    /** Queues a reconcile on the publisher thread; safe from any thread. */
    void scheduleReconcile() {
        pulse.execute(this::reconcileWithShare);
    }

    /**
     * Makes this device's queue and the share agree, in both directions.
     *
     * <p>Used both when the queue first comes back and when this device has been out of touch, and
     * it is the same job either way: whatever it thinks it is doing may be out of date, and the
     * share is what everyone else is going by.
     *
     * <ul>
     *   <li><b>Held here but not ours any more</b> — another device claimed it more recently while
     *       we were away. Dropped without touching the share, since the pages already written
     *       belong to whoever adopted it.</li>
     *   <li><b>Published by us but not held here</b> — the process ended and took the queue with
     *       it. Brought back, minus anything taken over in the meantime.</li>
     * </ul>
     *
     * <p>Runs on the publisher thread; the device applies its own queue edits on the main one,
     * where every other queue change happens.
     */
    private void reconcileWithShare() {
        final SmbDownloadState.ReconcilePlan plan;
        try {
            plan = SmbDownloadState.planReconcile(
                    Settings.getSmbClientId(),
                    device.snapshot(),
                    SmbDownloadStateStore.readAll(),
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
            // Either nothing was lost, or everything we had is gone -- taken over, or finished
            // elsewhere. Say where we are either way, so our file stops advertising claims we no
            // longer hold.
            publish();
        }
        if (!plan.restores.isEmpty()) {
            device.restore(plan.restores);
        }
    }

    // ---------- The all-devices view ----------

    /**
     * Every device's SMB downloads as one list, ready for the download screen.
     *
     * <p>This is the view no single file holds: it is computed from what each device published
     * under {@code state/}, with one entry per gallery even when two of them claim it.
     *
     * <p>Performs SMB I/O; call from a worker thread.
     */
    @NonNull
    public List<SmbTaskInfo> snapshotSharedTasks() {
        if (!SmbConnection.isConfigured()) {
            return new ArrayList<>();
        }
        try {
            String selfId = Settings.getSmbClientId();
            List<SmbDownloadState.Published> all = new ArrayList<>();
            for (SmbDownloadState.Published p : SmbDownloadStateStore.readAll()) {
                if (!p.state.clientId.equals(selfId)) {
                    all.add(p);
                }
            }
            // This device's own rows come from its queue, not from what it last managed to publish.
            // The file on the share is a broadcast to everyone else and lags every local action by
            // a round trip -- pausing something and watching the row carry on downloading, because
            // the read got there before the write, and a paused task then produces nothing further
            // to trigger another refresh. Locally there is no such doubt: this is the process doing
            // the work.
            all.add(new SmbDownloadState.Published(
                    device.snapshot(), true, System.currentTimeMillis()));
            List<SmbDownloadState.OwnedTask> merged = SmbDownloadState.merge(all);
            List<SmbTaskInfo> out = new ArrayList<>(merged.size());
            for (SmbDownloadState.OwnedTask o : merged) {
                out.add(SmbTaskInfo.of(o, selfId, galleryMetadata(o.task), rowStateOf(o, selfId)));
            }
            return out;
        } catch (Throwable e) {
            // The share being unreachable means we cannot say what anyone is downloading. An empty
            // list is the honest answer; the local list is unaffected either way.
            Log.w(TAG, "Could not read the shared task list", e);
            return new ArrayList<>();
        }
    }

    /**
     * What the share already knows about a queued gallery beyond its place in the queue.
     *
     * <p>Category, cover and the rest live in the gallery's own {@code metadata.json}, written the
     * moment it is enqueued. Copying them into {@code state/} as well would mean two records of the
     * same thing that can disagree, so the row reads the one that is authoritative.
     *
     * <p>Cached because the list refreshes on every finished page, and a gallery's metadata does
     * not change while it downloads. A miss is not cached: it means the owner has not written the
     * skeleton yet, which is a thing that stops being true.
     */
    private final Map<Long, GalleryInfo> metadataCache =
            java.util.Collections.synchronizedMap(new HashMap<>());

    @Nullable
    private GalleryInfo galleryMetadata(@NonNull SmbDownloadState.Task task) {
        GalleryInfo cached = metadataCache.get(task.gid);
        if (cached != null) {
            return cached;
        }
        GalleryInfo hint = new GalleryInfo();
        hint.gid = task.gid;
        hint.title = task.title;
        GalleryInfo read = SmbInventory.readGalleryMetadata(hint);
        if (read != null) {
            metadataCache.put(task.gid, read);
        }
        return read;
    }

    /**
     * How a row should be drawn, which is a different question for our tasks and everyone else's.
     *
     * <p>For this device there is a real answer and it is in this process. For another device
     * there is not: nothing it publishes about what it is doing can be trusted, because the moment
     * it loses contact is the moment it can no longer correct what it said. All that is knowable
     * from outside is whether it is still there — which is the file's mtime, not its contents.
     */
    private int rowStateOf(@NonNull SmbDownloadState.OwnedTask owned, @NonNull String selfId) {
        if (!owned.ownerAlive) {
            return com.hippo.ehviewer.dao.DownloadInfo.STATE_FAILED;   // drawn as "device offline"
        }
        if (!owned.clientId.equals(selfId)) {
            return com.hippo.ehviewer.dao.DownloadInfo.STATE_DOWNLOAD;
        }
        return device.localRowState(owned.task.gid);
    }

    /**
     * Whether some other device that is still alive has already claimed this gallery.
     *
     * <p>The check that stops two devices downloading the same thing. Performs SMB I/O; call from a
     * worker thread.
     */
    public boolean isClaimedElsewhere(long gid) {
        if (!SmbConnection.isConfigured()) {
            return false;
        }
        try {
            return SmbDownloadState.isClaimedByAnotherLiveClient(
                    SmbDownloadState.merge(SmbDownloadStateStore.readAll()),
                    gid, Settings.getSmbClientId());
        } catch (Throwable e) {
            // Unreachable share, unreadable files: let the download proceed. Downloading something
            // twice wastes bandwidth; refusing to download because the check failed loses the
            // gallery, which is worse.
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
     * Adopts a download whose owner has stopped beating.
     *
     * <p>Nothing is written to the other device's file — nobody ever writes another's. The claim
     * goes in this one, stamped now so it is unambiguously the later of the two, and the pair is
     * resolved by {@code SmbDownloadState.merge} preferring a live claimant over a dead one. The
     * abandoned copy disappears when that device comes back and notices, or is overruled by the
     * marker left behind when this device finishes.
     *
     * <p>Liveness is checked again here against a fresh read. The list a user tapped may be a
     * refresh old, and the owner may have woken up in between — adopting a download somebody is
     * actively doing would have two devices writing the same pages.
     *
     * <p>Performs SMB I/O; returns immediately and does the work on the publisher thread.
     *
     * @param onResult told what happened, on the main thread.
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
        final SmbDownloadState.TakeOverAssessment fresh;
        try {
            fresh = SmbDownloadState.assessTakeOver(
                    SmbDownloadState.merge(SmbDownloadStateStore.readAll()),
                    task.gid, Settings.getSmbClientId());
        } catch (Throwable e) {
            // Without a fresh read there is no way to know the owner is still gone, and adopting a
            // download two devices then run at once is the worse outcome.
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
        // Take it out of the abandoned queue. The one write this app ever makes to another
        // device's file, and only against one that has said nothing for STALE_AFTER_MS -- which is
        // the condition that says no one else is writing it. Leaving it would have the stale copy
        // resurface the moment this device finished and stopped claiming the gallery.
        if (!SmbDownloadStateStore.removeTask(task.ownerClientId, task.gid)) {
            // Not fatal: our claim is live and newer, so it wins the merge and the download goes
            // ahead. The abandoned entry may reappear later as an orphan of a gallery already on
            // the share, and taking that over again is harmless.
            Log.w(TAG, "Took over gid=" + task.gid + " but could not clear it from "
                    + task.ownerClientId);
        }
        device.enqueueAdopted(ctx, info);
        return TakeOverResult.TAKEN;
    }
}
