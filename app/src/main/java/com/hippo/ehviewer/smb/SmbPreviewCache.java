package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;

import com.hippo.ehviewer.storage.NetworkStorage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Fans preview reads out ahead of Conaco's serial executor into deterministic local files, so
 * per-cell loads never do SMB I/O on that thread. Pool sized by {@link SmbConcurrency#image()}.
 */
public final class SmbPreviewCache {

    private static final String TAG = "SmbPreviewCache";
    private static final String CACHE_SUBDIR = "smb_preview";
    private static final ThreadPoolExecutor PREFETCH_EXECUTOR = new ThreadPoolExecutor(
            SmbConcurrency.DEFAULT_IMAGE, SmbConcurrency.DEFAULT_IMAGE,
            10L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "smb-preview-prefetch");
                t.setDaemon(true);
                return t;
            });

    static {
        PREFETCH_EXECUTOR.allowCoreThreadTimeOut(true);
    }

    /** The pool, resized to the current setting; package-private so the benchmark measures it. */
    static ThreadPoolExecutor prefetchExecutor() {
        SmbConcurrency.resize(PREFETCH_EXECUTOR, SmbConcurrency.image());
        return PREFETCH_EXECUTOR;
    }

    /** Galleries we have already kicked off a prefetch for in this process. */
    private static final Set<Long> PREFETCHED_GIDS =
            Collections.synchronizedSet(new HashSet<>());

    /**
     * Outstanding prefetch tasks per gid (the dispatch task + one per page), so a gallery's
     * prefetch can be cancelled when its detail scene goes away. Guarded by its own monitor.
     */
    private static final Map<Long, List<Future<?>>> IN_FLIGHT = new HashMap<>();

    /** Lazily resolved and memoised cache directory so we don't re-stat it per call. */
    private static volatile File sCacheDir;

    private SmbPreviewCache() {}

    @NonNull
    private static File cacheDir() {
        File dir = sCacheDir;
        if (dir != null) {
            return dir;
        }
        synchronized (SmbPreviewCache.class) {
            dir = sCacheDir;
            if (dir == null) {
                dir = new File(EhApplication.getInstance().getCacheDir(), CACHE_SUBDIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    // Don't memoise: a later call may succeed (disk full / permissions
                    // cleared). Otherwise every subsequent cacheFileFor() would return a
                    // path under a non-existent dir and every fetchOne() write would
                    // silently fail with no log + no retry (the gid is already in the
                    // PREFETCHED_GIDS dedup set).
                    Log.w(TAG, "Failed to create SMB preview cache dir: " + dir);
                    return dir;
                }
                sCacheDir = dir;
            }
        }
        return dir;
    }

    /**
     * Returns the deterministic cache file for a (gid, index) pair. The file may or may
     * not exist on disk — callers must check via {@link File#isFile()} before reading.
     */
    @NonNull
    public static File cacheFileFor(long gid, int index) {
        return new File(cacheDir(), gid + "-" + index);
    }

    /**
     * Parallel prefetch of a gallery's previews, once per process. The fan-out loop itself runs
     * pooled — inline it stuttered a UI frame on big galleries.
     */
    public static void prefetchGallery(long gid, @Nullable String title, int count) {
        if (count <= 0 || !NetworkStorage.active().isConfigured()) {
            return;
        }
        if (!PREFETCHED_GIDS.add(gid)) {
            return;
        }
        final GalleryInfo lookup = NetworkStorage.lookupKey(gid, title);
        // One short-lived dispatch task; count-many per-page tasks are queued from inside it.
        track(gid, prefetchExecutor().submit(() -> dispatchPages(lookup, gid, count)));
    }

    private static void dispatchPages(@NonNull GalleryInfo lookup, long gid, int count) {
        final AtomicInteger remaining = new AtomicInteger(count);
        for (int i = 0; i < count; i++) {
            // Stop queuing more work if this gallery was cancelled while we were dispatching
            // (cancelGallery interrupts the dispatch task and drops the gid from PREFETCHED_GIDS).
            if (Thread.currentThread().isInterrupted() || !PREFETCHED_GIDS.contains(gid)) {
                break;
            }
            final int index = i;
            track(gid, prefetchExecutor().submit(() -> {
                // A queued task may run after the gallery was cancelled; bail cheaply.
                if (!PREFETCHED_GIDS.contains(gid)) {
                    return;
                }
                try {
                    fetchOne(lookup, index);
                } catch (Throwable e) {
                    Log.w(TAG, "prefetch failed gid=" + gid + " index=" + index, e);
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        synchronized (IN_FLIGHT) {
                            IN_FLIGHT.remove(gid);
                        }
                        Log.i(TAG, "prefetch complete gid=" + gid + " count=" + count);
                    }
                }
            }));
        }
    }

    private static void track(long gid, @NonNull Future<?> future) {
        synchronized (IN_FLIGHT) {
            List<Future<?>> list = IN_FLIGHT.get(gid);
            if (list == null) {
                list = new ArrayList<>();
                IN_FLIGHT.put(gid, list);
            }
            list.add(future);
        }
    }

    private static void fetchOne(@NonNull GalleryInfo lookup, int index) throws IOException {
        File target = cacheFileFor(lookup.gid, index);
        if (target.isFile() && target.length() > 0) {
            return;
        }
        long tPerf0 = android.os.SystemClock.elapsedRealtime();
        InputStream remote = NetworkStorage.active().files().openImageInputStream(lookup, index);
        if (remote == null) {
            return;
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = remote;
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        if (!tmp.renameTo(target)) {
            // Another worker raced us — drop the tmp file.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        android.util.Log.i("SmbPerf", "preview idx=" + index + " gid=" + lookup.gid + " bytes=" + target.length()
                + " " + (android.os.SystemClock.elapsedRealtime() - tPerf0) + "ms thr=" + Thread.currentThread().getName());
    }

    private static void closeQuietly(@Nullable java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    /** Cancels outstanding tasks and the dedup mark when the detail scene goes away. */
    public static void cancelGallery(long gid) {
        // Drop the mark first so any task that slips past cancellation bails at its guard.
        PREFETCHED_GIDS.remove(gid);
        List<Future<?>> list;
        synchronized (IN_FLIGHT) {
            list = IN_FLIGHT.remove(gid);
        }
        if (list != null) {
            for (Future<?> f : list) {
                f.cancel(true);
            }
        }
    }

    /** Cancels and deletes the gallery's cached files (on share-side delete). */
    public static void evictGallery(long gid) {
        cancelGallery(gid);
        String prefix = gid + "-";
        File[] files = cacheDir().listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.getName().startsWith(prefix) && !f.delete()) {
                Log.w(TAG, "Failed to delete cached preview " + f);
            }
        }
    }
}
