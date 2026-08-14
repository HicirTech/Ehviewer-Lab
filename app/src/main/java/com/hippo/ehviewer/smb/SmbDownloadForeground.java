package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.storage.NetworkStorage;

/**
 * The foreground service handle and the words in its notification. Reads the queue only through
 * the snapshot handed to {@link #update} — no lock shared with the ledger.
 */
final class SmbDownloadForeground {

    private static final String TAG = "SmbDirectDownloader";

    @Nullable
    private SmbDownloadService service;

    /** Starts the service (keeps the process alive past UI tear-down) if none is attached. */
    void ensureStarted(@NonNull Context context) {
        synchronized (this) {
            if (service != null) {
                return;
            }
        }
        try {
            SmbDownloadService.start(context);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to start SmbDownloadService", e);
        }
    }

    void attach(@NonNull SmbDownloadService svc) {
        synchronized (this) {
            service = svc;
        }
    }

    void detach() {
        synchronized (this) {
            service = null;
        }
    }

    /** Renders one queue snapshot into the notification; null means leave it alone. */
    void update(@NonNull Context ctx, @Nullable SmbTaskLedger.NotificationContent content) {
        SmbDownloadService svc;
        synchronized (this) {
            svc = service;
        }
        if (svc == null || content == null) {
            return;
        }
        String title;
        String text;
        int max;
        int prog;
        boolean indeterminate;
        if (content.active == null) {
            title = ctx.getString(R.string.smb_notif_queue_title, NetworkStorage.active().displayName());
            text = ctx.getString(R.string.smb_notif_queue_waiting, content.queued);
            max = 0;
            prog = 0;
            indeterminate = true;
        } else {
            title = content.active.title != null
                    ? content.active.title : ("gid " + content.active.gid);
            String extras = content.queued > 0
                    ? ctx.getString(R.string.smb_notif_extra_waiting, content.queued) : "";
            if (content.total > 0) {
                text = ctx.getString(R.string.smb_notif_progress_count,
                        content.finished, content.total, extras);
                max = content.total;
                prog = content.finished;
                indeterminate = false;
            } else {
                text = ctx.getString(R.string.smb_notif_progress_starting, extras);
                max = 0;
                prog = 0;
                indeterminate = true;
            }
        }
        svc.updateNotification(title, text, max, prog, indeterminate);
    }

    /** Stops the service when the queue has gone idle. */
    void stopIfIdle(boolean idle, @Nullable Context ctx) {
        boolean haveService;
        synchronized (this) {
            haveService = service != null;
        }
        if (haveService && idle && ctx != null) {
            SmbDownloadService.stop(ctx);
        }
    }
}
