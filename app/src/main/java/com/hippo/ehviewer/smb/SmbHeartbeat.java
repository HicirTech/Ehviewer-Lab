package com.hippo.ehviewer.smb;

import com.hippo.ehviewer.storage.DownloadState;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class SmbHeartbeat {

    private static final String TAG = "SmbDirectDownloader";

    // Several beats per STALE_AFTER_MS, so a few missed ones do not mean dead.
    private static final long INTERVAL_MS = 20_000L;

    interface Shell {
        /** True when the write landed. */
        boolean publishSelf();

        boolean shouldBeat();

        /** The silence was long enough that others may have acted; go and look. */
        void onBackFromSilence();
    }

    private final Shell shell;

    // One thread: publishes serialize, so the share always ends on the newest state.
    private final ScheduledExecutorService thread =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "smb-state-publisher");
                t.setDaemon(true);
                return t;
            });
    @Nullable
    private ScheduledFuture<?> beating;

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

    void publish() {
        sync();
        execute(this::publishOnce);
    }

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

    // Counted dead by others yet never restarted, so no startup re-read will come.
    private void beat() {
        long before = lastPublishedAtMillis;
        publishOnce();
        boolean wasAway = before > 0L
                && System.currentTimeMillis() - before >= DownloadState.STALE_AFTER_MS;
        if (wasAway) {
            Log.i(TAG, "Out of touch with the share for "
                    + (System.currentTimeMillis() - before) + "ms; re-reading the queue");
            shell.onBackFromSilence();
        }
    }
}
