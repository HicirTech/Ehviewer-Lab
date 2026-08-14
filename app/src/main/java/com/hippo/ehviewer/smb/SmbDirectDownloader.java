package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.lib.yorozuya.collect.LongList;
import com.hippo.unifile.UniFile;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.ehviewer.EhDB;
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

    /**
     * Galleries whose copy in phone storage should go once they are complete on the share.
     *
     * <p>All that separates a move from a download. The pages come across by themselves: the
     * download asks {@code SpiderDen.contain} for each page, and a page the phone already holds is
     * put on the share instead of fetched. What is left is the deleting, and only a move should do
     * that -- an ordinary download that happened to find some pages locally has not been asked to
     * take anything away.
     *
     * <p>In this process only. Killed mid-move, the gallery comes out copied rather than moved,
     * which is the harmless direction to fail in.
     */
    private final java.util.Set<Long> movingFromPhone =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

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
        movingFromPhone.add(info.gid);
        start(context, info);
    }

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
            retired.remove(info.gid);
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
        SmbSpiderStorage.unmarkGidAsSmbTarget(gid);
        notifyObservers();
        updateNotification();
        SimpleHandler.getInstance().post(this::pumpOnMainThread);
        maybeStopService();
    }

    /**
     * Whether SMB is a thing this app is doing at all right now.
     *
     * <p>The same pair every other SMB surface gates on — Local Inventory, the save option on a
     * gallery, the drawer entry, the download list. The downloader was the one place that never
     * asked, so turning the feature off hid the tasks from the list while their pages carried on
     * being written to the share.
     */
    private static boolean smbAvailable() {
        return Settings.getSmbSaveEnabled() && SmbConnection.isConfigured();
    }

    /**
     * Called when the master switch may have moved, or a screen wants the queue brought up to date.
     *
     * <p>Off means off: the downloads stop, the heartbeat stops, the service goes away and the app
     * is local-only. Back on means going and looking at the share again rather than trusting
     * whatever this process was last told — another device may have taken work over in between.
     */
    public void onSmbAvailabilityChanged() {
        boolean available = smbAvailable();
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
        try {
            publisher.execute(this::reconcileWithShare);
        } catch (Throwable e) {
            Log.w(TAG, "Could not schedule a reconcile", e);
        }
    }

    /** Null until the first check; then whatever {@link #smbAvailable()} last said. */
    @Nullable
    private volatile Boolean lastKnownAvailable;

    /**
     * Galleries this process has finished with, so a reconcile cannot bring them back.
     *
     * <p>A task absent from memory usually means the process lost it. It can also mean it just
     * completed, and a reconcile reading a file written before that completion cannot tell the
     * difference. Per-process and small: the only thing it has to outlive is a stale read.
     */
    private final java.util.Set<Long> retired =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

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
        List<ActiveJob> toRelease = new ArrayList<>();
        boolean hadAnything;
        synchronized (lock) {
            hadAnything = !active.isEmpty() || !queue.isEmpty();
            for (ActiveJob j : active.values()) {
                toRelease.add(j);
                paused.put(j.info.gid, j.info);
            }
            active.clear();
            paused.putAll(queue);
            queue.clear();
        }
        for (ActiveJob j : toRelease) {
            try {
                j.queen.removeOnSpiderListener(j.listener);
                SpiderQueen.releaseSpiderQueen(j.queen, SpiderQueen.MODE_DOWNLOAD);
            } catch (Throwable e) {
                Log.w(TAG, "Failed to release SpiderQueen on suspend gid=" + j.info.gid, e);
            }
        }
        if (hadAnything) {
            Log.i(TAG, "SMB is unavailable; holding " + toRelease.size() + " download(s)");
        }
        syncHeartbeat();
        notifyObservers();
        maybeStopService();
    }

    private void syncHeartbeat() {
        boolean hasWork;
        synchronized (lock) {
            hasWork = !queue.isEmpty() || !active.isEmpty() || !paused.isEmpty();
        }
        // Nothing to beat about with the feature switched off, and nowhere to beat to.
        hasWork = hasWork && smbAvailable();
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
        if (!SmbConnection.isConfigured()) {
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
                if (!heldGids.contains(t.gid) && !retired.contains(t.gid)) {
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
                    // Carry the progress back too. Without it the next publish says 0 of 181 for
                    // a gallery that is most of the way done, and every other device believes it
                    // -- the count on the share is the only thing they have to go by.
                    progress.put(t.gid, new int[]{t.finished, t.total});
                }
                // Everything comes back held, whatever it was doing when contact was lost --
                // the same as an ordinary download after a restart, which waits to be started
                // rather than picking itself up. Restarting a transfer is a decision with a cost
                // attached, and the moment the share reappears is not the moment to make it on
                // the user's behalf.
                synchronized (lock) {
                    paused.put(t.gid, info);
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
                    snapshotClientState(), true, System.currentTimeMillis()));
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
        synchronized (lock) {
            if (active.containsKey(owned.task.gid)) {
                return com.hippo.ehviewer.dao.DownloadInfo.STATE_DOWNLOAD;
            }
            if (queue.containsKey(owned.task.gid)) {
                return com.hippo.ehviewer.dao.DownloadInfo.STATE_WAIT;
            }
            return com.hippo.ehviewer.dao.DownloadInfo.STATE_NONE;
        }
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
                tasks.add(taskFor(job.info));
            }
            for (GalleryInfo gi : queue.values()) {
                tasks.add(taskFor(gi));
            }
            for (GalleryInfo gi : paused.values()) {
                tasks.add(taskFor(gi));
            }
        }
        return new SmbDownloadState.ClientState(
                Settings.getSmbClientId(), Settings.getSmbDeviceName(), tasks);
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
    private SmbDownloadState.Task taskFor(@NonNull GalleryInfo info) {
        int[] p = progress.get(info.gid);
        int finished = p != null ? p[0] : 0;
        int total = p != null && p[1] > 0 ? p[1] : info.pages;
        Long claimed = claimedAt.get(info.gid);
        return new SmbDownloadState.Task(info.gid, info.token, info.title,
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
            retired.add(gid);
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
                    SmbGalleryLifecycle.deleteGalleryFolder(finalInfo);
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to delete SMB folder on cancel gid=" + gid, e);
                }
            });
        }
        SmbSpiderStorage.unmarkGidAsSmbTarget(gid);
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
            SmbSpiderStorage.unmarkGidAsSmbTarget(info.gid);
            Log.w(TAG, "SMB direct download skipped for gid=" + info.gid + ": " + e.getMessage());
        } catch (Throwable e) {
            SmbSpiderStorage.unmarkGidAsSmbTarget(info.gid);
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
        retired.add(info.gid);
        if (job == null) {
            return;
        }
        Log.i(TAG, "SMB direct download finished gid=" + info.gid);
        // Drop the claim promptly: the gallery is on the share now, and leaving it listed would
        // have other devices think it is still being worked on.
        publishState();
        // Finalize metadata + cover on the IO pool.
        final Context ctx = appContext != null ? appContext : EhApplication.getInstance();
        final boolean wasMove = movingFromPhone.remove(info.gid);
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                SmbGalleryLifecycle.finalizeDownloadedGallery(ctx, info);
            } catch (Throwable e) {
                Log.e(TAG, "SMB finalize failed for gid=" + info.gid, e);
            }
            if (wasMove) {
                dropPhoneCopy(ctx, info);
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
            // Moves used to have their own branch here, because they were their own kind of work.
            // They are downloads now, so they arrive as active jobs and queued entries like
            // everything else.
            if (active.isEmpty()) {
                if (queued > 0) {
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
            stop = service != null && active.isEmpty() && queue.isEmpty();
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
