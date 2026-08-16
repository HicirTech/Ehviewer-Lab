package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.streampipe.InputStreamPipe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fans preview reads out ahead of Conaco's serial executor — into a bounded in-memory buffer
 * only (#129), the same shape as {@link SmbCoverPrefetch}; the share stays the sole durable
 * copy. Disk appears only as an anonymous decode shim that dies with its pipe. A missing
 * buffer entry just falls back to the share. Pool sized by {@link SmbConcurrency#image()}.
 */
public final class SmbPreviewCache {

    private static final String TAG = "SmbPreviewCache";

    // One preview grid of full-size pages (a bounded slice of up to 20, each 0.1-1MB); LRU past
    // this, evictees fall back to the share. Covers make do with 8MB of much smaller files.
    static final int MAX_BUFFERED_BYTES = 16 * 1024 * 1024;

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

    /** Access-ordered, so overflow drops the least recently shown page. Keyed gid:index. */
    private static final LinkedHashMap<String, byte[]> BUFFER =
            new LinkedHashMap<>(64, 0.75f, true);
    private static int sBufferedBytes;

    /** Galleries we have already kicked off a prefetch for in this process. LRU-bounded: an
     * evicted gid merely allows a redundant (and idempotent) prefetch much later. */
    private static final Set<Long> PREFETCHED_GIDS = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<Long, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<Long, Boolean> eldest) {
                    return size() > 256;
                }
            }));

    /**
     * Outstanding prefetch tasks per gid (the dispatch task + one per page), so a gallery's
     * prefetch can be cancelled when its detail scene goes away. Guarded by its own monitor.
     */
    private static final Map<Long, List<Future<?>>> IN_FLIGHT = new HashMap<>();

    /**
     * Where earlier builds staged previews as named files. Swept once per process so an upgrade
     * does not leave the old cache sitting there looking trustworthy; plantable by tests.
     */
    private static volatile File sLegacyDir;
    private static boolean sLegacySwept;

    private SmbPreviewCache() {}

    /**
     * Parallel prefetch of a gallery's previews, once per process. The fan-out loop itself runs
     * pooled — inline it stuttered a UI frame on big galleries.
     */
    public static void prefetchGallery(long gid, @Nullable String title, int count) {
        if (count <= 0 || !NetworkStorage.active().isConfigured()) {
            return;
        }
        sweepLegacyOnce();
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
        String key = keyOf(lookup.gid, index);
        synchronized (BUFFER) {
            if (BUFFER.containsKey(key)) {
                return;
            }
        }
        long tPerf0 = android.os.SystemClock.elapsedRealtime();
        InputStream in = NetworkStorage.active().files().openImageInputStream(lookup, index);
        if (in == null) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            IOUtils.closeQuietly(in);
        }
        byte[] bytes = out.toByteArray();
        if (bytes.length == 0) {
            return;
        }
        put(key, bytes);
        Log.i("SmbPerf", "preview idx=" + index + " gid=" + lookup.gid + " bytes=" + bytes.length
                + " " + (android.os.SystemClock.elapsedRealtime() - tPerf0) + "ms thr="
                + Thread.currentThread().getName());
    }

    /** Pipe over the buffered page, or null (caller reads the share). Anonymous shim on open(). */
    @Nullable
    public static InputStreamPipe pipeFor(long gid, int index) {
        final byte[] bytes;
        synchronized (BUFFER) {
            bytes = BUFFER.get(keyOf(gid, index));
        }
        if (bytes == null) {
            return null;
        }
        return new InputStreamPipe() {
            private File shim;
            private FileInputStream fis;

            @Override public void obtain() {}

            @Override public void release() {}

            @Override
            public InputStream open() throws IOException {
                if (fis != null) {
                    throw new IllegalStateException("Please close it first");
                }
                shim = File.createTempFile("smb_preview_", null, shimDir());
                try (FileOutputStream os = new FileOutputStream(shim)) {
                    os.write(bytes);
                }
                fis = new FileInputStream(shim);
                return fis;
            }

            @Override
            public void close() {
                IOUtils.closeQuietly(fis);
                fis = null;
                if (shim != null) {
                    //noinspection ResultOfMethodCallIgnored
                    shim.delete();
                    shim = null;
                }
            }
        };
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

    /**
     * Forgets a gallery's buffered previews (on share-side delete or re-sync — either can make
     * the buffered bytes disagree with the share, and the share wins).
     */
    public static void evictGallery(long gid) {
        cancelGallery(gid);
        String prefix = gid + ":";
        synchronized (BUFFER) {
            Iterator<Map.Entry<String, byte[]>> it = BUFFER.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, byte[]> e = it.next();
                if (e.getKey().startsWith(prefix)) {
                    sBufferedBytes -= e.getValue().length;
                    it.remove();
                }
            }
        }
    }

    @NonNull
    private static String keyOf(long gid, int index) {
        return gid + ":" + index;
    }

    private static void put(@NonNull String key, @NonNull byte[] bytes) {
        synchronized (BUFFER) {
            byte[] previous = BUFFER.put(key, bytes);
            if (previous != null) {
                sBufferedBytes -= previous.length;
            }
            sBufferedBytes += bytes.length;
            evictOverflow();
        }
    }

    private static void evictOverflow() {
        Iterator<Map.Entry<String, byte[]>> it = BUFFER.entrySet().iterator();
        while (sBufferedBytes > MAX_BUFFERED_BYTES && it.hasNext()) {
            Map.Entry<String, byte[]> eldest = it.next();
            sBufferedBytes -= eldest.getValue().length;
            it.remove();
        }
    }

    private static File shimDir() {
        return SmbShims.dir();
    }

    /**
     * Deletes the named-file preview cache earlier builds left behind (#129). Once per process;
     * the directory itself goes too, so an upgraded device does not carry a dead cache around.
     */
    private static synchronized void sweepLegacyOnce() {
        if (sLegacySwept) {
            return;
        }
        try {
            File dir = sLegacyDir;
            if (dir == null) {
                dir = new File(EhApplication.getInstance().getCacheDir(), "smb_preview");
                sLegacyDir = dir;
            }
            File[] leftovers = dir.listFiles();
            if (leftovers != null) {
                for (File f : leftovers) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        } catch (Throwable e) {
            Log.w(TAG, "Could not sweep the legacy preview cache", e);
        }
        sLegacySwept = true;
    }
}
