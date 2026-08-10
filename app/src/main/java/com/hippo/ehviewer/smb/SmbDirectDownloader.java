package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderQueen;
import com.hippo.lib.image.Image;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Standalone background downloader for "Save to SMB" galleries.
 * <p>
 * Bypasses the normal {@link com.hippo.ehviewer.download.DownloadManager} entirely so SMB-saved
 * galleries never appear in the Downloads list. Internally uses a {@link SpiderQueen} in
 * {@link SpiderQueen#MODE_DOWNLOAD} which, combined with {@code SpiderDen} routing writes through
 * {@link SmbStorage}, downloads every page directly into the SMB share.
 * <p>
 * State:
 * <ul>
 *   <li>{@code queue} — galleries waiting to start, FIFO, deduped against {@code active}.</li>
 *   <li>{@code active} — galleries currently being downloaded by a {@link SpiderQueen}.</li>
 * </ul>
 * <p>
 * A {@link SmbDownloadService} foreground service is started while any work exists, giving the
 * process enough lifecycle priority to keep downloading after the user leaves the gallery view
 * or locks the screen. The service is stopped automatically once both maps are empty.
 */
public final class SmbDirectDownloader {

    private static final String TAG = "SmbDirectDownloader";
    private static final int MAX_CONCURRENT = 1;

    private static final SmbDirectDownloader INSTANCE = new SmbDirectDownloader();

    private final Object lock = new Object();
    // FIFO + dedup: LinkedHashMap preserves insertion order and key lookup is O(1).
    private final LinkedHashMap<Long, GalleryInfo> queue = new LinkedHashMap<>();
    private final Map<Long, ActiveJob> active = new HashMap<>();
    /** Paused jobs (preserve order so the user can see them in the task list). */
    private final LinkedHashMap<Long, GalleryInfo> paused = new LinkedHashMap<>();
    /** Last seen progress per gid so notification updates survive listener churn. */
    private final Map<Long, int[]> progress = new HashMap<>();
    /**
     * When this device took each gallery on. Published so another device can tell whose claim on
     * the same gallery is the more recent one; see {@code SmbDownloadState.merge}.
     */
    private final Map<Long, Long> claimedAt = new HashMap<>();
    /** For a gallery taken over from a device that went away, who it was taken from. */
    private final Map<Long, String> takenOverFrom = new HashMap<>();

    /** Move-to-SMB batches in flight. Shares the same foreground notification surface. */
    private final Map<Integer, MoveBatch> moveBatches = new HashMap<>();
    private int nextMoveBatchId = 1;
    private final CopyOnWriteArrayList<TaskObserver> observers = new CopyOnWriteArrayList<>();
    @Nullable
    private SmbDownloadService service;
    /**
     * Written by {@link #start} / {@link #attachService} from any thread, read by main-thread
     * {@code pumpOnMainThread} / {@code updateNotification}. {@code volatile} keeps the writes
     * visible without a full lock; the field is otherwise idempotent (only flipped from null to
     * a process-lived application context).
     */
    @Nullable
    private volatile Context appContext;

    public static SmbDirectDownloader getInstance() {
        return INSTANCE;
    }

    private SmbDirectDownloader() {}

    /** Enqueue a gallery for SMB save. No-ops if it is already active or queued. */
    public void start(@NonNull Context context, @NonNull GalleryInfo info) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
        boolean shouldStartService;
        synchronized (lock) {
            if (active.containsKey(info.gid) || queue.containsKey(info.gid)) {
                return;
            }
            // Pulling a paused job back is treated as "enqueue".
            paused.remove(info.gid);
            queue.put(info.gid, info);
            if (!claimedAt.containsKey(info.gid)) {
                claimedAt.put(info.gid, System.currentTimeMillis());
            }
            shouldStartService = service == null;
        }
        if (shouldStartService) {
            // Foreground service keeps the process alive past UI tear-down / screen lock.
            try {
                SmbDownloadService.start(appContext);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to start SmbDownloadService", e);
            }
        }
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

    // ---------- Task monitor API ----------

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
    private final java.util.concurrent.atomic.AtomicBoolean restoreStarted =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Writes this device's queue to the share, and starts or stops the heartbeat to match whether
     * there is anything left to beat about.
     *
     * <p>Called at every structural change — something queued, started, paused, resumed, cancelled
     * or finished — because those are what another device needs to see promptly. An enqueue in
     * particular has to land before the download does, since a claim nobody can see is a claim that
     * does not prevent anyone downloading the same gallery twice.
     */
    private void publishState() {
        syncHeartbeat();
        try {
            publisher.execute(this::publishNow);
        } catch (Throwable e) {
            // Only if the executor is shutting down, which it never does in practice.
            Log.w(TAG, "Could not schedule a state publish", e);
        }
    }

    /**
     * When this device last got its file onto the share, by its own clock. Zero until it has.
     *
     * <p>The same quantity every other device is judging this one by: its file's mtime is the
     * moment of this write. So the device can work out for itself when it has been declared dead,
     * without asking anybody.
     */
    private volatile long lastPublishedAtMillis;

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
        if (!SmbStorage.isConfigured()) {
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
        if (!SmbStorage.isConfigured()) {
            return;
        }
        try {
            if (SmbDownloadStateStore.writeSelf(snapshotClientState())) {
                lastPublishedAtMillis = System.currentTimeMillis();
            }
        } catch (Throwable e) {
            // Failing to publish costs visibility to other devices, nothing local. The next beat
            // carries the same state, so there is nothing to recover here.
            Log.w(TAG, "Failed to publish download state", e);
        }
    }

    /**
     * Drops a task another device has taken over, without touching what is on the share.
     *
     * <p>Deliberately not {@link #cancel}: that deletes the gallery folder, and the folder now
     * belongs to whoever adopted the download. The pages already written are theirs to continue
     * from -- that is what makes a takeover a resumption rather than a restart.
     */
    private void yieldOnMainThread(long gid) {
        ActiveJob jobToRelease;
        synchronized (lock) {
            queue.remove(gid);
            paused.remove(gid);
            jobToRelease = active.remove(gid);
            progress.remove(gid);
            claimedAt.remove(gid);
            takenOverFrom.remove(gid);
        }
        if (jobToRelease != null) {
            try {
                jobToRelease.queen.removeOnSpiderListener(jobToRelease.listener);
                SpiderQueen.releaseSpiderQueen(jobToRelease.queen, SpiderQueen.MODE_DOWNLOAD);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to release SpiderQueen on yield gid=" + gid, e);
            }
        }
        SmbStorage.unmarkGidAsSmbTarget(gid);
        SmbAutoDownloadManager.getInstance().clearPending(gid);
        notifyObservers();
        updateNotification();
        SimpleHandler.getInstance().post(this::pumpOnMainThread);
        maybeStopService();
    }

    private void syncHeartbeat() {
        boolean hasWork;
        synchronized (lock) {
            hasWork = !queue.isEmpty() || !active.isEmpty() || !paused.isEmpty();
        }
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
     *
     * <p>What comes back is filtered through everyone else's state first. While this device was
     * away another may have taken a task over, and the only way to learn that is to look: the
     * claimer wrote it into its own file and could not touch this one. Whatever was lost is dropped
     * from this device's file here, which is what stops a takeover leaving two devices claiming the
     * same gallery for good.
     *
     * <p>Paused tasks stay paused — the user put them there. Anything else comes back queued,
     * including what was mid-download when the process died, since nothing is running now.
     */
    public void ensureRestored() {
        if (!restoreStarted.compareAndSet(false, true)) {
            return;
        }
        if (!SmbStorage.isConfigured()) {
            return;
        }
        try {
            publisher.execute(this::restoreNow);
        } catch (Throwable e) {
            Log.w(TAG, "Could not schedule a restore", e);
        }
    }

    private void restoreNow() {
        reconcileWithShare();
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
     * <p>Runs on the publisher thread; the queue edits are posted to the main one, where every
     * other queue change happens.
     */
    private void reconcileWithShare() {
        final String selfId = Settings.getSmbClientId();
        final List<SmbDownloadState.Task> missing;
        try {
            List<SmbDownloadState.Published> all = SmbDownloadStateStore.readAll();
            List<SmbDownloadState.OwnedTask> merged = SmbDownloadState.merge(all);

            SmbDownloadState.ClientState held = snapshotClientState();
            List<Long> stillOurs = gidsOf(SmbDownloadState.withoutTakenOver(held, merged));
            for (SmbDownloadState.Task t : held.tasks) {
                if (!stillOurs.contains(t.gid)) {
                    Log.i(TAG, "gid=" + t.gid + " was taken over elsewhere; standing down");
                    SimpleHandler.getInstance().post(() -> yieldOnMainThread(t.gid));
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
                if (!heldGids.contains(t.gid)) {
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
            publishState();
            return;
        }
        final Context ctx = appContext != null ? appContext : EhApplication.getInstance();
        SimpleHandler.getInstance().post(() -> {
            for (SmbDownloadState.Task t : missing) {
                GalleryInfo info = new GalleryInfo();
                info.gid = t.gid;
                info.token = t.token;
                info.title = t.title;
                info.pages = t.total;
                synchronized (lock) {
                    if (active.containsKey(t.gid) || queue.containsKey(t.gid)
                            || paused.containsKey(t.gid)) {
                        continue;   // already back, by whatever route
                    }
                    claimedAt.put(t.gid, t.claimedAt);
                    if (t.takenOverFrom != null) {
                        takenOverFrom.put(t.gid, t.takenOverFrom);
                    }
                }
                // Paused tasks stay paused -- the user put them there. Anything else comes back
                // queued, including what was mid-download when the process died, since nothing is
                // running now.
                if (t.state == SmbDownloadState.TaskState.PAUSED) {
                    synchronized (lock) {
                        paused.put(t.gid, info);
                    }
                } else {
                    start(ctx, info);
                }
            }
            notifyObservers();
            publishState();
        });
    }

    @NonNull
    private static List<Long> gidsOf(@NonNull List<SmbDownloadState.Task> tasks) {
        List<Long> out = new ArrayList<>(tasks.size());
        for (SmbDownloadState.Task t : tasks) {
            out.add(t.gid);
        }
        return out;
    }

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
        if (!SmbStorage.isConfigured()) {
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
                    snapshotClientState(), true, System.currentTimeMillis()));
            List<SmbDownloadState.OwnedTask> merged = SmbDownloadState.merge(all);
            List<SmbTaskInfo> out = new ArrayList<>(merged.size());
            for (SmbDownloadState.OwnedTask o : merged) {
                out.add(SmbTaskInfo.of(o, selfId, galleryMetadata(o.task)));
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
        GalleryInfo read = SmbStorage.readGalleryMetadata(hint);
        if (read != null) {
            metadataCache.put(task.gid, read);
        }
        return read;
    }

    /**
     * Whether some other device that is still alive has already claimed this gallery.
     *
     * <p>The check that stops two devices downloading the same thing. Performs SMB I/O; call from a
     * worker thread.
     */
    public boolean isClaimedElsewhere(long gid) {
        if (!SmbStorage.isConfigured()) {
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

    /**
     * This device's queue as the share should see it.
     *
     * <p>Package-private so a test can read what would have been published without a share to
     * publish to.
     */
    @NonNull
    SmbDownloadState.ClientState snapshotClientState() {
        List<SmbDownloadState.Task> tasks = new ArrayList<>();
        synchronized (lock) {
            for (ActiveJob job : active.values()) {
                tasks.add(taskFor(job.info, SmbDownloadState.TaskState.ACTIVE));
            }
            for (GalleryInfo gi : queue.values()) {
                tasks.add(taskFor(gi, SmbDownloadState.TaskState.QUEUED));
            }
            for (GalleryInfo gi : paused.values()) {
                tasks.add(taskFor(gi, SmbDownloadState.TaskState.PAUSED));
            }
        }
        return new SmbDownloadState.ClientState(
                Settings.getSmbClientId(), Settings.getSmbDeviceName(), tasks);
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
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
        final Context ctx = appContext;
        try {
            publisher.execute(() -> {
                TakeOverResult result = takeOverNow(ctx, task);
                SimpleHandler.getInstance().post(() -> onResult.onTakeOverFinished(result));
            });
        } catch (Throwable e) {
            Log.w(TAG, "Could not schedule a takeover for gid=" + task.gid, e);
            SimpleHandler.getInstance().post(() ->
                    onResult.onTakeOverFinished(TakeOverResult.FAILED));
        }
    }

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
        synchronized (lock) {
            claimedAt.put(task.gid, System.currentTimeMillis());
            takenOverFrom.put(task.gid, task.ownerClientId);
        }
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
        SimpleHandler.getInstance().post(() -> start(ctx, info));
        return TakeOverResult.TAKEN;
    }

    /** Caller holds {@code lock}. */
    private SmbDownloadState.Task taskFor(@NonNull GalleryInfo info,
                                          @NonNull SmbDownloadState.TaskState state) {
        int[] p = progress.get(info.gid);
        int finished = p != null ? p[0] : 0;
        int total = p != null && p[1] > 0 ? p[1] : info.pages;
        Long claimed = claimedAt.get(info.gid);
        return new SmbDownloadState.Task(info.gid, info.token, info.title, state,
                finished, total, claimed != null ? claimed : 0L, takenOverFrom.get(info.gid));
    }

    /**
     * Snapshot of every known SMB download task, ordered: active first, then queued, then paused.
     * Safe to call from any thread.
     */
    @NonNull
    public List<TaskSnapshot> snapshotTasks() {
        // Whoever is asking wants the whole queue, including whatever outlived the last process.
        ensureRestored();
        List<TaskSnapshot> out = new ArrayList<>();
        synchronized (lock) {
            for (ActiveJob job : active.values()) {
                int[] p = progress.get(job.info.gid);
                int finished = p != null ? p[0] : 0;
                int total = p != null ? p[1] : 0;
                out.add(new TaskSnapshot(job.info.gid, job.info.title, finished, total,
                        TaskSnapshot.State.ACTIVE));
            }
            for (GalleryInfo gi : queue.values()) {
                out.add(new TaskSnapshot(gi.gid, gi.title, 0, gi.pages,
                        TaskSnapshot.State.QUEUED));
            }
            for (GalleryInfo gi : paused.values()) {
                int[] p = progress.get(gi.gid);
                int finished = p != null ? p[0] : 0;
                int total = p != null ? p[1] : gi.pages;
                out.add(new TaskSnapshot(gi.gid, gi.title, finished, total,
                        TaskSnapshot.State.PAUSED));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Cancel a task by gid. Removes it from queue/paused immediately; for an active task,
     * releases the SpiderQueen on the main thread. The SMB-target mark is also cleared so a
     * subsequent download via DownloadManager (if the user chooses "to phone") would not be
     * silently re-routed to SMB.
     */
    public void cancel(long gid) {
        SimpleHandler.getInstance().post(() -> cancelOnMainThread(gid));
    }

    private void cancelOnMainThread(long gid) {
        ActiveJob jobToRelease = null;
        GalleryInfo infoForDelete = null;
        synchronized (lock) {
            GalleryInfo queued = queue.remove(gid);
            GalleryInfo wasPaused = paused.remove(gid);
            ActiveJob j = active.remove(gid);
            progress.remove(gid);
            claimedAt.remove(gid);
            takenOverFrom.remove(gid);
            if (j != null) {
                jobToRelease = j;
                infoForDelete = j.info;
            } else if (wasPaused != null) {
                infoForDelete = wasPaused;
            } else if (queued != null) {
                infoForDelete = queued;
            }
        }
        if (jobToRelease != null) {
            try {
                jobToRelease.queen.removeOnSpiderListener(jobToRelease.listener);
                SpiderQueen.releaseSpiderQueen(jobToRelease.queen, SpiderQueen.MODE_DOWNLOAD);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to release SpiderQueen on cancel gid=" + gid, e);
            }
        }
        // Wipe the on-share folder so partial pages don't accumulate. Run on the IO pool
        // because SMB delete is a network round trip. Must happen AFTER releasing the
        // SpiderQueen so we're not racing its writes.
        if (infoForDelete != null) {
            final GalleryInfo finalInfo = infoForDelete;
            IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                try {
                    SmbStorage.deleteGalleryFolder(finalInfo);
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to delete SMB folder on cancel gid=" + gid, e);
                }
            });
        }
        SmbStorage.unmarkGidAsSmbTarget(gid);
        // Allow this gid to be re-enqueued from the auto / manual paths in the same
        // process. Without this, the dedup set in SmbAutoDownloadManager would silently
        // drop every subsequent enqueue until the app restarts.
        SmbAutoDownloadManager.getInstance().clearPending(gid);
        notifyObservers();
        publishState();
        updateNotification();
        // Promote a queued job if a slot opened up.
        SimpleHandler.getInstance().post(this::pumpOnMainThread);
        maybeStopService();
    }

    /**
     * Pause a task. Active → release the queen but keep the gid in {@code paused} so the user
     * can resume later (the partially-saved pages on the share will be skipped by SpiderQueen's
     * existence check, giving "resume" semantics for free). Queued → just move to paused.
     */
    public void pause(long gid) {
        SimpleHandler.getInstance().post(() -> pauseOnMainThread(gid));
    }

    private void pauseOnMainThread(long gid) {
        ActiveJob jobToRelease = null;
        synchronized (lock) {
            GalleryInfo info;
            ActiveJob j = active.remove(gid);
            if (j != null) {
                jobToRelease = j;
                info = j.info;
            } else {
                info = queue.remove(gid);
            }
            if (info != null && !paused.containsKey(gid)) {
                paused.put(gid, info);
            }
        }
        if (jobToRelease != null) {
            try {
                jobToRelease.queen.removeOnSpiderListener(jobToRelease.listener);
                SpiderQueen.releaseSpiderQueen(jobToRelease.queen, SpiderQueen.MODE_DOWNLOAD);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to release SpiderQueen on pause gid=" + gid, e);
            }
        }
        notifyObservers();
        publishState();
        updateNotification();
        SimpleHandler.getInstance().post(this::pumpOnMainThread);
    }

    /** Resume a paused task by re-enqueueing it. No-op if the task isn't paused. */
    public void resume(long gid) {
        SimpleHandler.getInstance().post(() -> {
            GalleryInfo info;
            synchronized (lock) {
                info = paused.remove(gid);
                if (info == null) {
                    return;
                }
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

    /** Service lifecycle hooks. Called by {@link SmbDownloadService}. */
    void attachService(@NonNull SmbDownloadService svc) {
        synchronized (lock) {
            this.service = svc;
            if (this.appContext == null) {
                this.appContext = svc.getApplicationContext();
            }
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
        synchronized (lock) {
            this.service = null;
        }
    }

    private void pumpOnMainThread() {
        if (appContext == null) {
            return;
        }
        while (true) {
            GalleryInfo next;
            synchronized (lock) {
                if (active.size() >= MAX_CONCURRENT || queue.isEmpty()) {
                    break;
                }
                // pop head of queue
                Map.Entry<Long, GalleryInfo> first = queue.entrySet().iterator().next();
                queue.remove(first.getKey());
                next = first.getValue();
                if (active.containsKey(next.gid)) {
                    continue;
                }
            }
            startJob(next);
        }
        updateNotification();
        maybeStopService();
    }

    private void startJob(@NonNull GalleryInfo info) {
        // Mark BEFORE obtaining the queen so the SpiderDen it constructs immediately routes
        // to SMB. Unmarked in onJobFinish.
        SmbStorage.markGidAsSmbTarget(info.gid);
        try {
            SpiderQueen queen = SpiderQueen.obtainSpiderQueen(appContext, info, SpiderQueen.MODE_DOWNLOAD);
            ListenerImpl listener = new ListenerImpl(info);
            queen.addOnSpiderListener(listener);
            synchronized (lock) {
                active.put(info.gid, new ActiveJob(queen, listener, info));
                progress.put(info.gid, new int[]{0, 0}); // [finished, total]
            }
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
            SmbStorage.unmarkGidAsSmbTarget(info.gid);
            Log.w(TAG, "SMB direct download skipped for gid=" + info.gid + ": " + e.getMessage());
        } catch (Throwable e) {
            SmbStorage.unmarkGidAsSmbTarget(info.gid);
            Log.e(TAG, "Failed to start SMB direct download gid=" + info.gid, e);
        }
    }

    private void onJobFinish(@NonNull GalleryInfo info) {
        final ActiveJob job;
        synchronized (lock) {
            job = active.remove(info.gid);
            progress.remove(info.gid);
            claimedAt.remove(info.gid);
            takenOverFrom.remove(info.gid);
        }
        if (job == null) {
            return;
        }
        Log.i(TAG, "SMB direct download finished gid=" + info.gid);
        // Drop the claim promptly: the gallery is on the share now, and leaving it listed would
        // have other devices think it is still being worked on.
        publishState();
        // Allow re-enqueue after a normal finish (e.g. user wants to re-download to
        // overwrite, or a future feature triggers another save).
        SmbAutoDownloadManager.getInstance().clearPending(info.gid);
        // Finalize metadata + cover on the IO pool.
        final Context ctx = appContext != null ? appContext : EhApplication.getInstance();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                SmbStorage.finalizeDownloadedGallery(ctx, info);
            } catch (Throwable e) {
                Log.e(TAG, "SMB finalize failed for gid=" + info.gid, e);
            }
        });
        // releaseSpiderQueen must run on the main thread.
        SimpleHandler.getInstance().post(() -> {
            try {
                job.queen.removeOnSpiderListener(job.listener);
                SpiderQueen.releaseSpiderQueen(job.queen, SpiderQueen.MODE_DOWNLOAD);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to release SpiderQueen gid=" + info.gid, e);
            }
            // Keep the gid marked so any subsequent READs from LocalInventoryScene
            // continue to resolve to SMB even before the process restarts.
            pumpOnMainThread();
        });
    }

    private void updateNotification() {
        SmbDownloadService svc;
        String title;
        String text;
        int max;
        int prog;
        boolean indeterminate;
        // Resolve a context up front for string lookups. Fall back to the global
        // EhApplication instance if the per-instance appContext hasn't been latched yet
        // (e.g. notification fires before the service has called attachService).
        final Context ctx = appContext != null ? appContext : EhApplication.getInstance();
        synchronized (lock) {
            svc = service;
            if (svc == null) {
                return;
            }
            int queued = queue.size();
            // Move batches take priority in the notification when no download job is active,
            // and are also surfaced as a sibling line when one is. They use the same foreground
            // notification so the user always sees a single "SMB is doing something" indicator.
            if (active.isEmpty()) {
                if (!moveBatches.isEmpty()) {
                    MoveBatch mv = moveBatches.values().iterator().next();
                    String titleSubject = mv.currentItemTitle != null
                            ? mv.currentItemTitle
                            : ctx.getString(R.string.smb_notif_move_progress, mv.finished, mv.total);
                    title = ctx.getString(R.string.smb_notif_move_title, titleSubject);
                    text = moveBatches.size() > 1
                            ? ctx.getString(R.string.smb_notif_move_progress_more,
                                    mv.finished, mv.total, moveBatches.size() - 1)
                            : ctx.getString(R.string.smb_notif_move_progress, mv.finished, mv.total);
                    max = mv.total;
                    prog = mv.finished;
                    indeterminate = mv.total <= 0;
                } else if (queued > 0) {
                    title = ctx.getString(R.string.smb_notif_queue_title);
                    text = ctx.getString(R.string.smb_notif_queue_waiting, queued);
                    max = 0;
                    prog = 0;
                    indeterminate = true;
                } else {
                    return;
                }
            } else {
                ActiveJob job = active.values().iterator().next();
                int[] p = progress.get(job.info.gid);
                int finished = p != null ? p[0] : 0;
                int total = p != null ? p[1] : 0;
                title = job.info.title != null ? job.info.title : ("gid " + job.info.gid);
                StringBuilder extras = new StringBuilder();
                if (queued > 0) {
                    extras.append(ctx.getString(R.string.smb_notif_extra_waiting, queued));
                }
                if (!moveBatches.isEmpty()) {
                    extras.append(ctx.getString(R.string.smb_notif_extra_move, moveBatches.size()));
                }
                if (total > 0) {
                    text = ctx.getString(R.string.smb_notif_progress_count, finished, total, extras.toString());
                    max = total;
                    prog = finished;
                    indeterminate = false;
                } else {
                    text = ctx.getString(R.string.smb_notif_progress_starting, extras.toString());
                    max = 0;
                    prog = 0;
                    indeterminate = true;
                }
            }
        }
        svc.updateNotification(title, text, max, prog, indeterminate);
    }

    private void maybeStopService() {
        boolean stop;
        Context ctx;
        synchronized (lock) {
            stop = service != null && active.isEmpty() && queue.isEmpty() && moveBatches.isEmpty();
            ctx = appContext;
        }
        if (stop && ctx != null) {
            SmbDownloadService.stop(ctx);
        }
    }

    private static final class ActiveJob {
        final SpiderQueen queen;
        final SpiderQueen.OnSpiderListener listener;
        final GalleryInfo info;

        ActiveJob(SpiderQueen queen, SpiderQueen.OnSpiderListener listener, GalleryInfo info) {
            this.queen = queen;
            this.listener = listener;
            this.info = info;
        }
    }

    /**
     * Tracks a move-to-SMB batch so its progress can be shown on the same foreground
     * notification as SMB downloads. Created by {@link #beginMoveBatch} and updated as the
     * caller copies each gallery.
     */
    public final class MoveBatchHandle {
        private final int id;

        MoveBatchHandle(int id) { this.id = id; }

        /** Mark a new item as starting (1-based progress is computed automatically). */
        public void onItemStart(@Nullable String itemTitle) {
            ensureService();
            synchronized (lock) {
                MoveBatch b = moveBatches.get(id);
                if (b != null) {
                    b.currentItemTitle = itemTitle;
                }
            }
            updateNotification();
        }

        /** Mark the most recently-started item as finished. */
        public void onItemDone() {
            synchronized (lock) {
                MoveBatch b = moveBatches.get(id);
                if (b != null) {
                    b.finished = Math.min(b.total, b.finished + 1);
                }
            }
            updateNotification();
        }

        /** Tear down the batch, removing it from the notification surface. */
        public void finish() {
            synchronized (lock) {
                moveBatches.remove(id);
            }
            updateNotification();
            maybeStopService();
        }

        private void ensureService() {
            boolean shouldStart;
            synchronized (lock) {
                shouldStart = service == null;
            }
            if (shouldStart && appContext != null) {
                try { SmbDownloadService.start(appContext); } catch (Throwable ignored) {}
            }
        }
    }

    private static final class MoveBatch {
        final int total;
        int finished;
        @Nullable String currentItemTitle;

        MoveBatch(int total) { this.total = Math.max(0, total); }
    }

    /**
     * Register a move-to-SMB batch with the foreground notification surface. Caller drives the
     * notification by calling {@code onItemStart} / {@code onItemDone} for each gallery and
     * {@code finish()} when done. Starts the {@link SmbDownloadService} if it isn't already up.
     */
    @NonNull
    public MoveBatchHandle beginMoveBatch(@NonNull Context context, int total) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
        int id;
        boolean shouldStartService;
        synchronized (lock) {
            id = nextMoveBatchId++;
            moveBatches.put(id, new MoveBatch(total));
            shouldStartService = service == null;
        }
        if (shouldStartService) {
            try { SmbDownloadService.start(appContext); } catch (Throwable e) {
                Log.w(TAG, "Failed to start SmbDownloadService for move", e);
            }
        }
        updateNotification();
        return new MoveBatchHandle(id);
    }

    private final class ListenerImpl implements SpiderQueen.OnSpiderListener {
        private final GalleryInfo info;

        ListenerImpl(GalleryInfo info) {
            this.info = info;
        }

        @Override
        public void onGetPages(int pages) {
            synchronized (lock) {
                int[] p = progress.get(info.gid);
                if (p != null) {
                    p[1] = pages;
                }
            }
            updateNotification();
        }

        @Override
        public void onGet509(int index) {}

        @Override
        public void onPageDownload(int index, long contentLength, long receivedSize, int bytesRead) {}

        @Override
        public void onPageSuccess(int index, int finished, int downloaded, int total) {
            synchronized (lock) {
                int[] p = progress.get(info.gid);
                if (p != null) {
                    p[0] = finished;
                    p[1] = total;
                }
            }
            updateNotification();
            notifyObservers();
        }

        @Override
        public void onPageFailure(int index, String error, int finished, int downloaded, int total) {
            synchronized (lock) {
                int[] p = progress.get(info.gid);
                if (p != null) {
                    p[0] = finished;
                    p[1] = total;
                }
            }
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
