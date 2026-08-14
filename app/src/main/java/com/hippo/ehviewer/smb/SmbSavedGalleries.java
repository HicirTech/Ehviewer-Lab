package com.hippo.ehviewer.smb;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.Settings;
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
 * Which galleries are finished and sitting on the share, so a list can say so (#83).
 *
 * <p>Browsing e-hentai gives no hint that a gallery is already saved to the NAS, so the same one
 * gets downloaded twice, or read over the network when a local copy was there all along. The only
 * marker a card had was {@code @id/downloaded}, and that one knows about the phone's download list
 * and nothing else.
 *
 * <h3>What counts as saved</h3>
 *
 * <p>A folder under {@code download/} whose gid <b>nobody has claimed</b> in {@code state/}. Both
 * halves are needed, because the folder is created the moment a gallery is enqueued
 * ({@code SmbMetadata.writeMetadataSkeleton}) — so its presence alone cannot tell a finished
 * gallery from one that has not started. A claim is precisely "some device is still working on
 * this", and it is dropped when the download finishes.
 *
 * <p>Live claim or dead one, the answer is the same: not saved. A download whose device died is not
 * on the share in any useful sense, and it is already visible in the download list waiting to be
 * adopted. Marking it saved would send the reader at a gallery with holes in it.
 *
 * <p>This deliberately does not check that every page is present. That costs a metadata read and a
 * directory listing <em>per gallery</em>, which a fifty-card page cannot afford. Subtracting claims
 * gets the same answer for every gallery this app has finished, in two directory reads, and the
 * gap it leaves — a download interrupted so long ago that its claim is gone — is the subject of a
 * separate issue.
 *
 * <h3>Cost</h3>
 *
 * <p>One enumeration of {@code download/} plus one read of each device's file under {@code state/}.
 * Measured on the real share: 85 ms to enumerate 200 folders, and about 64 ms per small file, so a
 * couple of hundred milliseconds in total. Cheap, but not free and not instant, which is why
 * nothing ever waits on it: {@link #snapshot()} answers from what is already in hand and a refresh
 * happens behind it.
 */
public final class SmbSavedGalleries {

    private static final String TAG = "SmbSavedGalleries";

    /**
     * How long a snapshot is left alone before a refresh actually goes to the share.
     *
     * <p>Only bounds the pathological case — flicking between screens should not re-read the share
     * every time. Anything this device does that changes the answer calls {@link #invalidate()}, so
     * this is not how quickly a finished download shows up.
     */
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
     * Whether this gallery is finished and on the share.
     *
     * <p>Never blocks and never touches the share: a card asks this while binding, and binding
     * cannot wait on a NAS. Answers false until the first refresh lands.
     *
     * <p>The switch is checked here rather than only at refresh time, so that turning SMB off
     * takes effect on every screen at once. Left to the refresh, it would depend on which hook each
     * screen happened to use — the online list refreshes on resume and would clear immediately,
     * while History and Favourites refresh when their view is built and would keep showing marks
     * for a share the user has just switched off. A {@code SharedPreferences} read per card is not
     * a price worth paying to avoid.
     */
    public boolean contains(long gid) {
        return enabled() && saved.contains(gid);
    }

    /**
     * Brings the answer up to date behind whatever is already on screen.
     *
     * <p>Call on entering a list. Returns immediately; observers hear about it if it changed
     * anything.
     */
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

    /**
     * Marks what is held as out of date, so the next {@link #refresh()} really reads.
     *
     * <p>For the things this device does that change the answer — finishing a download, deleting a
     * gallery — where waiting out the TTL would leave the list wrong about something the user just
     * did themselves.
     */
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
        return SmbConnection.isConfigured() && Settings.getSmbSaveEnabled();
    }

    /**
     * Reads the share. Returns null when it could not be read at all — the caller keeps the
     * previous answer rather than replacing it with an empty one, because an unreachable NAS would
     * otherwise blank every marker in the list and read as "none of these are saved".
     */
    private static Set<Long> read() {
        long t0 = SystemClock.elapsedRealtime();
        try {
            List<SmbInventory.GalleryRef> refs = SmbInventory.listGalleryRefs();
            Set<Long> gids = new HashSet<>(refs.size() * 2);
            for (SmbInventory.GalleryRef ref : refs) {
                long gid = SmbPaths.parseGid(ref.folderName);
                if (gid != SmbPaths.NOT_A_GALLERY) {
                    gids.add(gid);
                }
            }
            long tListed = SystemClock.elapsedRealtime();

            int claimed = 0;
            for (SmbDownloadState.Published p : SmbDownloadStateStore.readAll()) {
                for (SmbDownloadState.Task t : p.state.tasks) {
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
