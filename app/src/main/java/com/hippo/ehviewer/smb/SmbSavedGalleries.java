package com.hippo.ehviewer.smb;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.storage.DownloadState;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.storage.NetworkStorageSettings;
import com.hippo.lib.yorozuya.SimpleHandler;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Saved = folders minus every claim, live or dead: a folder exists from enqueue on. */
public final class SmbSavedGalleries {

    private static final String TAG = "SmbSavedGalleries";

    // Own changes call invalidate(); this only delays other devices' additions.
    private static final long TTL_MS = 30_000L;

    private static final SmbSavedGalleries INSTANCE = new SmbSavedGalleries();

    public static SmbSavedGalleries getInstance() {
        return INSTANCE;
    }

    /** Called on the main thread. */
    public interface Observer {
        void onSavedGalleriesChanged();
    }

    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "smb-saved-galleries");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    /** Immutable once published, so readers on the main thread never see a half-built set. */
    @NonNull
    private volatile Set<Long> saved = Collections.emptySet();
    private volatile long loadedAt = 0L;

    private SmbSavedGalleries() {
    }

    public void addObserver(@NonNull Observer o) {
        observers.addIfAbsent(o);
    }

    public void removeObserver(@NonNull Observer o) {
        observers.remove(o);
    }

    /** Never blocks or touches the share; false until the first refresh. */
    public boolean contains(long gid) {
        return enabled() && saved.contains(gid);
    }

    /** Refreshes in the background; observers hear about changes. */
    public void refresh() {
        if (!enabled()) {
            publish(Collections.<Long>emptySet());
            return;
        }
        if (SystemClock.elapsedRealtime() - loadedAt < TTL_MS) {
            return;
        }
        refreshNow();
    }

    public void invalidate() {
        loadedAt = 0L;
    }

    private void refreshNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return;   // one already on the way; it will publish for both of us
        }
        try {
            worker.execute(() -> {
                try {
                    Set<Long> fresh = read();
                    if (fresh == null) {
                        return;
                    }
                    // The switch may have gone off mid-read; its empty set must stand.
                    if (!enabled()) {
                        publish(Collections.<Long>emptySet());
                        return;
                    }
                    loadedAt = SystemClock.elapsedRealtime();
                    publish(fresh);
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (Throwable e) {
            refreshing.set(false);
            Log.w(TAG, "Could not schedule a refresh", e);
        }
    }

    private static boolean enabled() {
        return NetworkStorage.active().isConfigured() && NetworkStorageSettings.isEnabled();
    }

    /** Null on failure, so the caller keeps the previous answer. */
    private static Set<Long> read() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            List<GalleryRef> refs = NetworkStorage.active().inventory().listGalleryRefs();
            Set<Long> gids = new HashSet<>(refs.size() * 2);
            for (GalleryRef ref : refs) {
                long gid = NetworkStorage.active().parseGalleryGid(ref.folderName);
                if (gid != NetworkStorage.NOT_A_GALLERY) {
                    gids.add(gid);
                }
            }
            long tListed = SystemClock.elapsedRealtime();

            int claimed = 0;
            for (DownloadState.Published p : NetworkStorage.active().stateStore().readAll()) {
                for (DownloadState.Task t : p.state.tasks) {
                    if (gids.remove(t.gid)) {
                        claimed++;
                    }
                }
            }
            Log.i("SmbPerf", "savedGalleries n=" + gids.size() + " claimed=" + claimed
                    + " list=" + (tListed - t0) + "ms state=" + (SystemClock.elapsedRealtime() - tListed)
                    + "ms thr=" + Thread.currentThread().getName());
            return Collections.unmodifiableSet(gids);
        } catch (Throwable e) {
            Log.w(TAG, "Could not read which galleries are on the share", e);
            return null;
        }
    }

    private void publish(@NonNull Set<Long> fresh) {
        if (saved.equals(fresh)) {
            return;
        }
        saved = fresh;
        SimpleHandler.getInstance().post(() -> {
            for (Observer o : observers) {
                try {
                    o.onSavedGalleriesChanged();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
