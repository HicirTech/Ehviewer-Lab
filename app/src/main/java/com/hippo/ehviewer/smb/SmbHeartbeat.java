package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * When to publish (#59): the single publisher thread, the fixed-delay beat, and the
 * returned-from-silence check. What a publish writes is the {@link Shell}'s business.
 */
final class SmbHeartbeat {

    private static final String TAG = "SmbDirectDownloader";

    // 20s: progress is not worth a ~64ms share write per page, and this stays well inside
    // STALE_AFTER_MS so a few missed beats don't get this device declared dead.
    private static final long INTERVAL_MS = 20_000L;

    interface Shell {
        /** Write the current state to the share. True when the write landed. */
        boolean publishSelf();

        /** Whether there is anything to beat about. */
        boolean shouldBeat();

        /** The silence was long enough that others may have acted; go and look. */
        void onBackFromSilence();
    }

    private final Shell shell;

    // One thread, deliberately: publishes serialize, and each snapshots when it runs, so the
    // last write on the share is always the newest state.
    private final ScheduledExecutorService thread =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "smb-state-publisher");
                t.setDaemon(true);
                return t;
            });
    @Nullable
    private ScheduledFuture<?> beating;

    /** When this device last reached the share — the number everyone else judges it by. */
    private volatile long lastPublishedAtMillis;

    SmbHeartbeat(@NonNull Shell shell) {
        this.shell = shell;
    }

    /** Runs work on the publisher thread, serialised with every publish. */
    void execute(@NonNull Runnable work) {
        try {
            thread.execute(work);
        } catch (Throwable e) {
            Log.w(TAG, "Could not schedule on the publisher thread", e);
        }
    }

    /** Publishes soon, and re-syncs whether the beat should be running. */
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

    // A device silent past STALE_AFTER_MS has been declared dead by everyone else; before
    // trusting its own queue again it must go and look (the process never died, so nothing
    // else will).
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
