package com.hippo.ehviewer.smb;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.storage.DownloadState;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.SimpleHandler;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Which galleries are finished on the share (#83): folders in download/ minus every claim in
 * state/ (folders exist from enqueue-time, so presence alone cannot mean finished; claimed —
 * live or dead — means not saved). Two directory reads total; nothing ever waits on it.
 */
public final class SmbSavedGalleries {

    private static final String TAG = "SmbSavedGalleries";

    // Bounds screen-flicking only; own changes call invalidate() and skip the TTL.
    private static final long TTL_MS = 30_000L;

    private static final SmbSavedGalleries INSTANCE = new SmbSavedGalleries();

    public static SmbSavedGalleries getInstance() {
        return INSTANCE;
    }

    /** Told, on the main thread, when the answer has changed. */
    public interface Observer {
        void onSavedGalleriesChanged();
    }

    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();

    /**
     * One thread, so two refreshes cannot run at once. Reading the share twice concurrently is
     * wasted round trips at best, and the two results racing to be stored at worst.
     */
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

    /**
     * Never blocks, never touches the share (cards ask while binding); false until the first
     * refresh. The switch is checked per call so turning SMB off clears every screen at once.
     */
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

    /** Skips the TTL after own changes (finish, delete). */
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
                    // Asked again on the way out. A read takes a few hundred milliseconds, and the
                    // switch can go off inside that window: the refresh that noticed would have
                    // published an empty set already, and this one would then put the old answer
                    // back. Note that read() itself cannot notice — listGalleryRefs only checks
                    // that a host is configured, which it still is.
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
        return NetworkStorage.active().isConfigured() && Settings.getSmbSaveEnabled();
    }

    /** Reads the share; null on failure so the caller keeps the previous answer. */
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
