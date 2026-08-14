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
 * <p>It talks to the device side only through {@link Device} — six questions, no reaching into
 * queue maps — which is what makes the two lives separately testable, and is the seam #100 would
 * lift into a capability interface (a backend with no second client has no board at all).
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

    /**
     * How often this device refreshes its file under {@code state/} while it has work.
     *
     * <p>This is both the heartbeat and how progress reaches the other devices — the write carries
     * the current finished/total and, by happening at all, tells everyone this device is still
     * here. Progress is deliberately <em>not</em> published per page: a write costs about 64 ms
     * against the share, and a three-hundred page gallery would spend most of a minute on it for a
     * number nobody is watching that closely.
     *
     * <p>Comfortably inside {@link SmbDownloadStateStore#STALE_AFTER_MS}, so several beats can be
     * missed — a congested share, a moment of bad WiFi — before anyone concludes this device died
     * and offers its downloads to someone else.
     */
    private static final long HEARTBEAT_INTERVAL_MS = 20_000L;

    /**
     * Publishing runs on one thread of its own, which is what keeps two writes from overlapping:
     * each task snapshots the queue when it runs rather than when it was scheduled, so the last
     * write to reach the share is always the newest state rather than whichever happened to finish
     * last.
     */
    private final ScheduledExecutorService publisher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "smb-state-publisher");
                t.setDaemon(true);
                return t;
            });
    @Nullable
    private ScheduledFuture<?> heartbeat;
    /** Restoring is a once-per-process affair, whichever entry point asks for it first. */
    private final AtomicBoolean restoreStarted = new AtomicBoolean();

    /**
     * When this device last got its file onto the share, by its own clock. Zero until it has.
     *
     * <p>The same quantity every other device is judging this one by: its file's mtime is the
     * moment of this write. So the device can work out for itself when it has been declared dead,
     * without asking anybody.
     */
    private volatile long lastPublishedAtMillis;

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
        syncHeartbeat();
        try {
            publisher.execute(this::publishNow);
        } catch (Throwable e) {
            // Only if the executor is shutting down, which it never does in practice.
            Log.w(TAG, "Could not schedule a state publish", e);
        }
    }

    /**
     * A heartbeat: say where we are, and notice if we have been away.
     *
     * <p>The write is the whole of the normal path — no read, nothing to reconcile. What it also
     * does is check its own last success: if this device has not managed to publish for
     * {@link SmbDownloadStateStore#STALE_AFTER_MS}, then by everyone else's reckoning it is dead
     * and its downloads are up for adoption, whatever it thinks it is doing.
     *
     * <p>That case is not hypothetical and it is not the same as crashing. Signal drops for a
     * couple of minutes and comes back; the process never died, so it never restores, and left to
     * itself it would carry on writing pages into a folder somebody else had taken over. So a
     * device coming back from silence goes and looks at the real state of the queue before
     * trusting its own.
     */
    private void beatNow() {
        if (!SmbConnection.isConfigured()) {
            return;
        }
        long before = lastPublishedAtMillis;
        publishNow();
        boolean wasAway = before > 0L
                && System.currentTimeMillis() - before >= SmbDownloadStateStore.STALE_AFTER_MS;
        if (wasAway) {
            Log.i(TAG, "Out of touch with the share for "
                    + (System.currentTimeMillis() - before) + "ms; re-reading the queue");
            reconcileWithShare();
        }
    }

    private void publishNow() {
        if (!smbAvailable()) {
            return;
        }
        try {
            if (SmbDownloadStateStore.writeSelf(device.snapshot())) {
                lastPublishedAtMillis = System.currentTimeMillis();
            }
        } catch (Throwable e) {
            // Failing to publish costs visibility to other devices, nothing local. The next beat
            // carries the same state, so there is nothing to recover here.
            Log.w(TAG, "Failed to publish download state", e);
        }
    }

    /** Starts or stops the heartbeat to match whether the device has work and SMB is on at all. */
    void syncHeartbeat() {
        // Nothing to beat about with the feature switched off, and nowhere to beat to.
        boolean hasWork = device.hasWork() && smbAvailable();
        synchronized (publisher) {
            if (hasWork && heartbeat == null) {
                heartbeat = publisher.scheduleWithFixedDelay(this::beatNow,
                        HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
            } else if (!hasWork && heartbeat != null) {
                heartbeat.cancel(false);
                heartbeat = null;
            }
        }
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
        try {
            publisher.execute(this::reconcileWithShare);
        } catch (Throwable e) {
            Log.w(TAG, "Could not schedule a reconcile", e);
        }
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
        final String selfId = Settings.getSmbClientId();
        final List<SmbDownloadState.Task> missing;
        try {
            List<SmbDownloadState.Published> all = SmbDownloadStateStore.readAll();
            List<SmbDownloadState.OwnedTask> merged = SmbDownloadState.merge(all);

            SmbDownloadState.ClientState held = device.snapshot();
            List<Long> stillOurs = gidsOf(SmbDownloadState.withoutTakenOver(held, merged));
            for (SmbDownloadState.Task t : held.tasks) {
                if (!stillOurs.contains(t.gid)) {
                    Log.i(TAG, "gid=" + t.gid + " was taken over elsewhere; standing down");
                    device.yieldTask(t.gid);
                }
            }

            SmbDownloadState.ClientState published = null;
            for (SmbDownloadState.Published p : all) {
                if (p.state.clientId.equals(selfId)) {
                    published = p.state;
                    break;
                }
            }
            if (published == null) {
                return;   // nothing of ours has ever been published
            }
            List<Long> heldGids = gidsOf(held.tasks);
            List<SmbDownloadState.Task> back = new ArrayList<>();
            for (SmbDownloadState.Task t : SmbDownloadState.withoutTakenOver(published, merged)) {
                if (!heldGids.contains(t.gid) && !device.isRetired(t.gid)) {
                    back.add(t);
                }
            }
            missing = back;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to reconcile with the share", e);
            return;
        }
        if (missing.isEmpty()) {
            // Either nothing was lost, or everything we had is gone -- taken over, or finished
            // elsewhere. Say where we are either way, so our file stops advertising claims we no
            // longer hold.
            publish();
            return;
        }
        device.restore(missing);
    }

    @NonNull
    private static List<Long> gidsOf(@NonNull List<SmbDownloadState.Task> tasks) {
        List<Long> out = new ArrayList<>(tasks.size());
        for (SmbDownloadState.Task t : tasks) {
            out.add(t.gid);
        }
        return out;
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
        try {
            publisher.execute(() -> {
                TakeOverResult result = takeOverNow(appContext, task);
                SimpleHandler.getInstance().post(() -> onResult.onTakeOverFinished(result));
            });
        } catch (Throwable e) {
            Log.w(TAG, "Could not schedule a takeover for gid=" + task.gid, e);
            SimpleHandler.getInstance().post(() ->
                    onResult.onTakeOverFinished(TakeOverResult.FAILED));
        }
    }

    @NonNull
    private TakeOverResult takeOverNow(@NonNull Context ctx, @NonNull SmbTaskInfo task) {
        final String selfId = Settings.getSmbClientId();
        try {
            List<SmbDownloadState.OwnedTask> merged =
                    SmbDownloadState.merge(SmbDownloadStateStore.readAll());
            for (SmbDownloadState.OwnedTask o : merged) {
                if (o.task.gid != task.gid) {
                    continue;
                }
                if (o.clientId.equals(selfId)) {
                    return TakeOverResult.TAKEN;   // already ours, by whatever route
                }
                if (o.ownerAlive) {
                    return TakeOverResult.OWNER_RETURNED;
                }
                break;
            }
        } catch (Throwable e) {
            // Without a fresh read there is no way to know the owner is still gone, and adopting a
            // download two devices then run at once is the worse outcome.
            Log.w(TAG, "Could not confirm gid=" + task.gid + " is still orphaned", e);
            return TakeOverResult.FAILED;
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
