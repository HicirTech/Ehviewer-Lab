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

final class InventoryBadges {

    static final class Mark {
        @NonNull final String clientId;
        final float progress;

        Mark(@NonNull String clientId, float progress) {
            this.clientId = clientId;
            this.progress = progress;
        }

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

    // Others' progress only moves on a 20s heartbeat; own moves per page.
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

    void attach() {
        SmbDirectDownloader.getInstance().addTaskObserver(observer);
    }

    void detach() {
        SmbDirectDownloader.getInstance().removeTaskObserver(observer);
    }

    /** Rate-limited; the last call of a burst runs late rather than being dropped. */
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
            // Never throws: an unreachable share reads as no tasks.
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

    /** Progress 0-1; zero while the total is unknown. */
    static float fractionOf(@NonNull SmbTaskInfo t) {
        if (t.total <= 0) {
            return 0f;
        }
        return (float) t.finished / (float) t.total;
    }
}
