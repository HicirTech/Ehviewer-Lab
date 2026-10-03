package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderQueen;

import com.hippo.ehviewer.storage.DownloadState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Queue state with no side effects; a gid is in at most one of queue, active and paused. */
final class SmbTaskLedger {

    static final class ActiveJob {
        final SpiderQueen queen;
        final SpiderQueen.OnSpiderListener listener;
        final GalleryInfo info;

        ActiveJob(SpiderQueen queen, SpiderQueen.OnSpiderListener listener, GalleryInfo info) {
            this.queen = queen;
            this.listener = listener;
            this.info = info;
        }
    }

    static final class CancelOutcome {
        @Nullable final ActiveJob jobToRelease;
        @Nullable final GalleryInfo infoForDelete;

        CancelOutcome(@Nullable ActiveJob jobToRelease, @Nullable GalleryInfo infoForDelete) {
            this.jobToRelease = jobToRelease;
            this.infoForDelete = infoForDelete;
        }
    }

    static final class FinishOutcome {
        @Nullable final ActiveJob job;
        final boolean wasMove;

        FinishOutcome(@Nullable ActiveJob job, boolean wasMove) {
            this.job = job;
            this.wasMove = wasMove;
        }
    }

    static final class NotificationContent {
        @Nullable final GalleryInfo active;
        final int finished;
        final int total;
        final int queued;

        NotificationContent(@Nullable GalleryInfo active, int finished, int total, int queued) {
            this.active = active;
            this.finished = finished;
            this.total = total;
            this.queued = queued;
        }
    }

    private final Object lock = new Object();
    private final LinkedHashMap<Long, GalleryInfo> queue = new LinkedHashMap<>();
    private final Map<Long, ActiveJob> active = new HashMap<>();
    private final LinkedHashMap<Long, GalleryInfo> paused = new LinkedHashMap<>();
    /** Last seen progress per gid so notification updates survive listener churn. */
    private final Map<Long, int[]> progress = new HashMap<>();
    /** When this device took each gallery on; the later claim wins the merge. */
    private final Map<Long, Long> claimedAt = new HashMap<>();
    private final Map<Long, String> takenOverFrom = new HashMap<>();
    private final Set<Long> retired = Collections.synchronizedSet(new HashSet<>());
    private final Set<Long> movingFromPhone = Collections.synchronizedSet(new HashSet<>());

    /** False when it is already queued or running (nothing changed). */
    boolean enqueue(@NonNull GalleryInfo info, boolean asMove) {
        synchronized (lock) {
            if (active.containsKey(info.gid) || queue.containsKey(info.gid)) {
                // A rejected move must not flag the gid, or the running save drops the phone copy.
                return false;
            }
            if (asMove) {
                movingFromPhone.add(info.gid);
            }
            enqueueEpochs.merge(info.gid, 1L, Long::sum);
            paused.remove(info.gid);
            retired.remove(info.gid);
            queue.put(info.gid, info);
            if (!claimedAt.containsKey(info.gid)) {
                claimedAt.put(info.gid, System.currentTimeMillis());
            }
        }
        return true;
    }

    /** Null while the slots are full or the queue is empty. */
    @Nullable
    GalleryInfo nextToStart(int maxConcurrent) {
        synchronized (lock) {
            while (true) {
                if (active.size() >= maxConcurrent || queue.isEmpty()) {
                    return null;
                }
                Map.Entry<Long, GalleryInfo> first = queue.entrySet().iterator().next();
                queue.remove(first.getKey());
                if (!active.containsKey(first.getValue().gid)) {
                    return first.getValue();
                }
            }
        }
    }

    void jobStarted(@NonNull GalleryInfo info, @NonNull ActiveJob job) {
        synchronized (lock) {
            active.put(info.gid, job);
            progress.put(info.gid, new int[]{0, 0}); // [finished, total]
        }
    }

    @NonNull
    FinishOutcome finish(@NonNull GalleryInfo info) {
        ActiveJob job;
        synchronized (lock) {
            job = active.remove(info.gid);
            progress.remove(info.gid);
            claimedAt.remove(info.gid);
            takenOverFrom.remove(info.gid);
        }
        retired.add(info.gid);
        return new FinishOutcome(job, movingFromPhone.remove(info.gid));
    }

    void forgetFailedStart(long gid) {
        synchronized (lock) {
            claimedAt.remove(gid);
            takenOverFrom.remove(gid);
            retired.add(gid);
        }
    }

    private final Map<Long, Long> enqueueEpochs = new HashMap<>();

    /** The gid's enqueue count; unlike {@code retired}, finish and failed starts leave it alone. */
    long epochOf(long gid) {
        synchronized (lock) {
            Long epoch = enqueueEpochs.get(gid);
            return epoch == null ? 0L : epoch;
        }
    }

    /** Returns the job the caller must release, if any. */
    @Nullable
    ActiveJob pause(long gid) {
        synchronized (lock) {
            GalleryInfo info;
            ActiveJob job = active.remove(gid);
            info = job != null ? job.info : queue.remove(gid);
            if (info != null && !paused.containsKey(gid)) {
                paused.put(gid, info);
            }
            return job;
        }
    }

    @Nullable
    GalleryInfo takeOutPaused(long gid) {
        synchronized (lock) {
            return paused.remove(gid);
        }
    }

    @NonNull
    CancelOutcome cancel(long gid) {
        synchronized (lock) {
            GalleryInfo queued = queue.remove(gid);
            GalleryInfo wasPaused = paused.remove(gid);
            ActiveJob job = active.remove(gid);
            progress.remove(gid);
            claimedAt.remove(gid);
            takenOverFrom.remove(gid);
            // A cancelled move is fully off: a later plain save must not inherit the deletion.
            movingFromPhone.remove(gid);
            retired.add(gid);
            GalleryInfo infoForDelete =
                    job != null ? job.info : (wasPaused != null ? wasPaused : queued);
            return new CancelOutcome(job, infoForDelete);
        }
    }

    @Nullable
    ActiveJob yield(long gid) {
        synchronized (lock) {
            queue.remove(gid);
            paused.remove(gid);
            progress.remove(gid);
            claimedAt.remove(gid);
            takenOverFrom.remove(gid);
            // The other device finishes this task; a yielded move degrades to a copy.
            movingFromPhone.remove(gid);
            return active.remove(gid);
        }
    }

    /** Holds everything; returns the jobs the caller must release. */
    @NonNull
    List<ActiveJob> suspendAll() {
        synchronized (lock) {
            List<ActiveJob> released = new ArrayList<>(active.values());
            for (ActiveJob j : released) {
                paused.put(j.info.gid, j.info);
            }
            active.clear();
            paused.putAll(queue);
            queue.clear();
            return released;
        }
    }

    void restore(@NonNull List<DownloadState.Task> tasks) {
        synchronized (lock) {
            for (DownloadState.Task t : tasks) {
                if (active.containsKey(t.gid) || queue.containsKey(t.gid)
                        || paused.containsKey(t.gid)) {
                    continue;
                }
                GalleryInfo info = new GalleryInfo();
                info.gid = t.gid;
                info.token = t.token;
                info.title = t.title;
                info.pages = t.total;
                claimedAt.put(t.gid, t.claimedAt);
                if (t.takenOverFrom != null) {
                    takenOverFrom.put(t.gid, t.takenOverFrom);
                }
                progress.put(t.gid, new int[]{t.finished, t.total});
                paused.put(t.gid, info);
            }
        }
    }

    void stampAdoption(@NonNull SmbTaskInfo task) {
        synchronized (lock) {
            claimedAt.put(task.gid, System.currentTimeMillis());
            takenOverFrom.put(task.gid, task.ownerClientId);
        }
    }

    void updateProgress(long gid, int finished, int total) {
        synchronized (lock) {
            int[] p = progress.get(gid);
            if (p != null) {
                p[0] = finished;
                p[1] = total;
            }
        }
    }

    void updateTotal(long gid, int total) {
        synchronized (lock) {
            int[] p = progress.get(gid);
            if (p != null) {
                p[1] = total;
            }
        }
    }

    boolean hasWork() {
        synchronized (lock) {
            return !queue.isEmpty() || !active.isEmpty() || !paused.isEmpty();
        }
    }

    boolean isIdle() {
        synchronized (lock) {
            return active.isEmpty() && queue.isEmpty();
        }
    }

    boolean isRetired(long gid) {
        return retired.contains(gid);
    }

    int localRowState(long gid) {
        synchronized (lock) {
            if (active.containsKey(gid)) {
                return com.hippo.ehviewer.dao.DownloadInfo.STATE_DOWNLOAD;
            }
            if (queue.containsKey(gid)) {
                return com.hippo.ehviewer.dao.DownloadInfo.STATE_WAIT;
            }
            return com.hippo.ehviewer.dao.DownloadInfo.STATE_NONE;
        }
    }

    @NonNull
    DownloadState.ClientState clientState() {
        List<DownloadState.Task> tasks = new ArrayList<>();
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
        return new DownloadState.ClientState(
                Settings.getSmbClientId(), Settings.getSmbDeviceName(), tasks);
    }

    /** Caller holds {@code lock}. */
    private DownloadState.Task taskFor(@NonNull GalleryInfo info) {
        int[] p = progress.get(info.gid);
        int finished = p != null ? p[0] : 0;
        int total = p != null && p[1] > 0 ? p[1] : info.pages;
        Long claimed = claimedAt.get(info.gid);
        return new DownloadState.Task(info.gid, info.token, info.title,
                finished, total, claimed != null ? claimed : 0L, takenOverFrom.get(info.gid));
    }

    @NonNull
    List<SmbDirectDownloader.TaskSnapshot> taskSnapshots() {
        List<SmbDirectDownloader.TaskSnapshot> out = new ArrayList<>();
        synchronized (lock) {
            for (ActiveJob job : active.values()) {
                int[] p = progress.get(job.info.gid);
                out.add(new SmbDirectDownloader.TaskSnapshot(job.info.gid, job.info.title,
                        p != null ? p[0] : 0, p != null ? p[1] : 0,
                        SmbDirectDownloader.TaskSnapshot.State.ACTIVE));
            }
            for (GalleryInfo gi : queue.values()) {
                out.add(new SmbDirectDownloader.TaskSnapshot(gi.gid, gi.title, 0, gi.pages,
                        SmbDirectDownloader.TaskSnapshot.State.QUEUED));
            }
            for (GalleryInfo gi : paused.values()) {
                int[] p = progress.get(gi.gid);
                out.add(new SmbDirectDownloader.TaskSnapshot(gi.gid, gi.title,
                        p != null ? p[0] : 0, p != null ? p[1] : gi.pages,
                        SmbDirectDownloader.TaskSnapshot.State.PAUSED));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Null when there is nothing to show. */
    @Nullable
    NotificationContent notificationContent() {
        synchronized (lock) {
            int queued = queue.size();
            if (active.isEmpty()) {
                return queued > 0 ? new NotificationContent(null, 0, 0, queued) : null;
            }
            ActiveJob job = active.values().iterator().next();
            int[] p = progress.get(job.info.gid);
            return new NotificationContent(job.info,
                    p != null ? p[0] : 0, p != null ? p[1] : 0, queued);
        }
    }
}
