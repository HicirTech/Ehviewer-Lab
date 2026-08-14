/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.smb.SmbDownloadBoard;
import com.hippo.ehviewer.smb.SmbTaskInfo;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.SimpleHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The "someone is downloading this" badges (#77, #99): a rate-limited read of the share's claims,
 * delivered as gid → mark on the main thread. Only delivers when the answer changed.
 */
final class InventoryBadges {

    /** What a card needs to draw its badge: whose download, and how far along. */
    static final class Mark {
        @NonNull final String clientId;
        final float progress;

        Mark(@NonNull String clientId, float progress) {
            this.clientId = clientId;
            this.progress = progress;
        }

        /** Compared, not merely stored: an unchanged mark is a redraw not done. */
        @Override
        public boolean equals(@Nullable Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Mark)) {
                return false;
            }
            Mark other = (Mark) o;
            return clientId.equals(other.clientId)
                    && Float.compare(progress, other.progress) == 0;
        }

        @Override
        public int hashCode() {
            return clientId.hashCode() * 31 + Float.floatToIntBits(progress);
        }
    }

    interface Listener {
        /** Main thread; called only when the marks differ from the last delivery. */
        void onMarks(@NonNull Map<Long, Mark> marks);
    }

    // 2s: others' progress only moves on a 20s heartbeat; own moves per page.
    private static final long REFRESH_INTERVAL_MS = 2_000L;

    private final Executor executor;
    private final Listener listener;
    private final SmbDirectDownloader.TaskObserver observer = this::refresh;

    private long lastRefreshAt;
    private boolean refreshScheduled;
    @NonNull
    private Map<Long, Mark> delivered = Collections.emptyMap();

    InventoryBadges(@NonNull Executor executor, @NonNull Listener listener) {
        this.executor = executor;
        this.listener = listener;
    }

    /** Own downloads need no round trip to notice: the downloader says so directly. */
    void attach() {
        SmbDirectDownloader.getInstance().addTaskObserver(observer);
    }

    void detach() {
        SmbDirectDownloader.getInstance().removeTaskObserver(observer);
    }

    /** Re-reads claims, rate-limited; the last call of a burst is honoured late, not dropped. */
    void refresh() {
        long now = System.currentTimeMillis();
        long since = now - lastRefreshAt;
        if (since < REFRESH_INTERVAL_MS) {
            if (!refreshScheduled) {
                refreshScheduled = true;
                SimpleHandler.getInstance().postDelayed(() -> {
                    refreshScheduled = false;
                    refresh();
                }, REFRESH_INTERVAL_MS - since);
            }
            return;
        }
        lastRefreshAt = now;

        if (!NetworkStorage.active().isConfigured() || !Settings.getNetworkStorageEnabled()) {
            deliver(Collections.emptyMap());
            return;
        }
        executor.execute(() -> {
            // Never throws: an unreachable share is an empty list, and the cards simply show no
            // badges rather than the screen failing over a decoration.
            final Map<Long, Mark> marks = new HashMap<>();
            for (SmbTaskInfo t : SmbDownloadBoard.getInstance().snapshotSharedTasks()) {
                marks.put(t.gid, new Mark(t.ownerClientId, fractionOf(t)));
            }
            SimpleHandler.getInstance().post(() -> deliver(marks));
        });
    }

    private void deliver(@NonNull Map<Long, Mark> marks) {
        if (delivered.equals(marks)) {
            return;
        }
        delivered = marks;
        listener.onMarks(marks);
    }

    /** Progress 0-1; zero while the total is unknown (claimed before counted). */
    static float fractionOf(@NonNull SmbTaskInfo t) {
        if (t.total <= 0) {
            return 0f;
        }
        return (float) t.finished / (float) t.total;
    }
}
