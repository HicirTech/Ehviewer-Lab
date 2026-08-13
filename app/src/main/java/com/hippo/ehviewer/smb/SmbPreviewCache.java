package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;

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

import jcifs.smb.SmbFile;

/**
 * Parallelises SMB preview reads. Conaco's disk-load executor is a single-thread serial
 * pool, so all per-cell loads happen one after another and a 40-page gallery on a slow
 * share appears to load previews "one at a time".
 *
 * <p>This cache prefetches every page in a gallery in parallel and writes each one to a
 * deterministic local file. {@link SmbImageDataContainer#get()} then hits the local file
 * directly without doing any SMB I/O on Conaco's serial thread, so the visible loading
 * becomes effectively concurrent.
 *
 * <p>Bandwidth assumption: the local SMB share is treated as having effectively unlimited
 * bandwidth, so we fan out a worker pool (sized by {@link SmbConcurrency#image()}) per gallery
 * rather than per page. The cache directory is shared with the legacy on-demand temp
 * staging path so disk space is bounded by the same eviction (manual clear / cache wipe).
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

    /**
     * The prefetch pool, resized to whatever the image-concurrency setting says now.
     *
     * <p>Read through here rather than used directly so a change to the setting takes effect on the
     * next gallery instead of on the next launch. Package-private for the settings screen's
     * benchmark, which must measure the pool the app actually uses.
     */
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
     * Kicks off a parallel SMB → local prefetch for {@code count} previews of the gallery, exactly
     * once per process lifetime (until {@link #cancelGallery}). Safe to call from any thread;
     * returns immediately.
     *
     * <p>The fan-out loop itself runs on the prefetch pool rather than the caller's thread:
     * for big galleries allocating N lambdas + N {@code LinkedBlockingQueue.put} calls inline
     * took long enough to cause a visible one-frame stutter when the preview grid first bound
     * on the UI thread.
     */
    public static void prefetchGallery(long gid, @Nullable String title, int count) {
        if (count <= 0 || !SmbStorage.isConfigured()) {
            return;
        }
        if (!PREFETCHED_GIDS.add(gid)) {
            return;
        }
        final GalleryInfo lookup = SmbStorage.lookupKey(gid, title);
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
        SmbFile remote = SmbStorage.findSmbImageFileForPreview(lookup, index);
        if (remote == null) {
            return;
        }
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new java.io.BufferedInputStream(remote.getInputStream(), SmbStorage.SMB_IO_BUFFER);
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

    /**
     * Stop prefetching a gallery and forget that we started: cancels every outstanding dispatch /
     * per-page task and clears the dedup mark so a later visit re-prefetches. Called when the
     * gallery's detail scene goes away, so leaving the page doesn't leave the shared prefetch pool
     * busy reading previews nobody is looking at anymore. Already-finished tasks are simply no-ops.
     */
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

    /**
     * Cancels any prefetch and deletes the cached preview files for one gallery. Called when the
     * gallery is deleted from the share: the local copies would otherwise linger until the whole
     * cache directory is cleared, and be served for a gallery that no longer exists.
     *
     * <p>Files are named {@code <gid>-<index>} in one flat directory (see
     * {@link #cacheFileFor}), so this filters the listing by prefix.
     */
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
