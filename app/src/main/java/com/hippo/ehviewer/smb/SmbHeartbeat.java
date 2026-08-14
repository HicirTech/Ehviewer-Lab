package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The pulse of #59: one thread that periodically says "still here", and notices when it has
 * been away.
 *
 * <p>Everything about <em>when</em> to publish lives in this class — the fixed-delay schedule,
 * starting and stopping it as work appears and drains, and the returned-from-silence check.
 * What a publish actually <em>writes</em> is the shell's business, passed in as {@link Shell}:
 * this class never touches the share, the settings, or a queue. That is the whole trick of
 * keeping it 100 lines and testable.
 *
 * <p>The single thread is load-bearing, not an optimisation: it is what keeps two writes from
 * overlapping. Each task snapshots the queue when it runs rather than when it was scheduled, so
 * the last write to reach the share is always the newest state rather than whichever happened
 * to finish last. Reconciles and takeovers ride the same thread through {@link #execute} for
 * the same reason.
 */
final class SmbHeartbeat {

    private static final String TAG = "SmbDirectDownloader";

    /**
     * How often to beat while there is work.
     *
     * <p>This is both the heartbeat and how progress reaches the other devices — the write
     * carries the current finished/total and, by happening at all, tells everyone this device
     * is still here. Progress is deliberately <em>not</em> published per page: a write costs
     * about 64 ms against the share, and a three-hundred page gallery would spend most of a
     * minute on it for a number nobody is watching that closely.
     *
     * <p>Comfortably inside {@link SmbDownloadStateStore#STALE_AFTER_MS}, so several beats can
     * be missed — a congested share, a moment of bad WiFi — before anyone concludes this device
     * died and offers its downloads to someone else.
     */
    private static final long INTERVAL_MS = 20_000L;

    /** What the beat needs from its owner; nothing here does IO of its own. */
    interface Shell {
        /** Write the current state to the share. True when the write landed. */
        boolean publishSelf();

        /** Whether there is anything to beat about (work exists and SMB is on at all). */
        boolean shouldBeat();

        /** The silence was long enough that others may have acted; go and look. */
        void onBackFromSilence();
    }

    private final Shell shell;

    private final ScheduledExecutorService thread =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "smb-state-publisher");
                t.setDaemon(true);
                return t;
            });
    @Nullable
    private ScheduledFuture<?> beating;

    /**
     * When this device last got its file onto the share, by its own clock. Zero until it has.
     *
     * <p>The same quantity every other device is judging this one by: its file's mtime is the
     * moment of this write. So the device can work out for itself when it has been declared
     * dead, without asking anybody.
     */
    private volatile long lastPublishedAtMillis;

    SmbHeartbeat(@NonNull Shell shell) {
        this.shell = shell;
    }

    /** Runs work on the publisher thread, serialised with every publish. */
    void execute(@NonNull Runnable work) {
        try {
            thread.execute(work);
        } catch (Throwable e) {
            // Only if the executor is shutting down, which it never does in practice.
            Log.w(TAG, "Could not schedule on the publisher thread", e);
        }
    }

    /** Publishes soon, and re-syncs whether the beat should be running at all. */
    void publish() {
        sync();
        execute(this::publishOnce);
    }

    /** Starts or stops the schedule to match {@link Shell#shouldBeat}. */
    void sync() {
        boolean wanted = shell.shouldBeat();
        synchronized (thread) {
            if (wanted && beating == null) {
                beating = thread.scheduleWithFixedDelay(this::beat,
                        INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
            } else if (!wanted && beating != null) {
                beating.cancel(false);
                beating = null;
            }
        }
    }

    private void publishOnce() {
        if (shell.publishSelf()) {
            lastPublishedAtMillis = System.currentTimeMillis();
        }
    }

    /**
     * One beat: say where we are, and notice if we have been away.
     *
     * <p>The write is the whole of the normal path — no read, nothing to reconcile. What it also
     * does is check its own last success: if this device has not managed to publish for
     * {@link SmbDownloadStateStore#STALE_AFTER_MS}, then by everyone else's reckoning it is dead
     * and its downloads are up for adoption, whatever it thinks it is doing.
     *
     * <p>That case is not hypothetical and it is not the same as crashing. Signal drops for a
     * couple of minutes and comes back; the process never died, so it never restores, and left
     * to itself it would carry on writing pages into a folder somebody else had taken over. So a
     * device coming back from silence goes and looks at the real state of the queue before
     * trusting its own.
     */
    private void beat() {
        long before = lastPublishedAtMillis;
        publishOnce();
        boolean wasAway = before > 0L
                && System.currentTimeMillis() - before >= SmbDownloadStateStore.STALE_AFTER_MS;
        if (wasAway) {
            Log.i(TAG, "Out of touch with the share for "
                    + (System.currentTimeMillis() - before) + "ms; re-reading the queue");
            shell.onBackFromSilence();
        }
    }
}
