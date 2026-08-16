package com.hippo.ehviewer.smb;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.lib.yorozuya.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import jcifs.smb.SmbFile;

/**
 * The files inside one gallery's folder: pages, covers, spider info. Two rules: all writes are
 * atomic (temp name + rename on close, the #35 fix), and local bytes are decode shims that die
 * with the pipe — the share stays the only durable copy.
 */
public final class SmbGalleryFiles {

    private static final String TAG = "SmbStorage";

    private static final String SPIDER_INFO_FILE = ".ehviewer";

    /**
     * jcifs sizes SMB2 requests from the caller's array, so buffer size is throughput: 4KB
     * writes measured 0.5 MB/s, 256KB reaches 6.3 (rcv_buf_size does not help).
     */
    static final int SMB_IO_BUFFER = 256 * 1024;

    /**
     * One-shot in-memory echo of the just-published page (#138): SpiderQueen re-reads every page
     * it downloads (the plain-text check), which on this backend meant pulling the whole page
     * back from the NAS while the NAS was still writing. The echo answers that one read from
     * memory — consumed on read, hard-capped, never touching disk.
     */
    private static final int ECHO_MAX_BYTES = 32 * 1024 * 1024;
    private static final java.util.LinkedHashMap<String, byte[]> RECENT_WRITES =
            new java.util.LinkedHashMap<>(8, 0.75f, false);
    private static int sEchoBytes;

    private static void rememberWrite(long gid, int index, @NonNull byte[] bytes) {
        synchronized (RECENT_WRITES) {
            byte[] previous = RECENT_WRITES.put(gid + ":" + index, bytes);
            if (previous != null) {
                sEchoBytes -= previous.length;
            }
            sEchoBytes += bytes.length;
            java.util.Iterator<java.util.Map.Entry<String, byte[]>> it =
                    RECENT_WRITES.entrySet().iterator();
            while (sEchoBytes > ECHO_MAX_BYTES && it.hasNext()) {
                sEchoBytes -= it.next().getValue().length;
                it.remove();
            }
        }
    }

    @Nullable
    private static byte[] consumeWrite(long gid, int index) {
        synchronized (RECENT_WRITES) {
            byte[] bytes = RECENT_WRITES.remove(gid + ":" + index);
            if (bytes != null) {
                sEchoBytes -= bytes.length;
            }
            return bytes;
        }
    }

    private SmbGalleryFiles() {}

    /**
     * Spider-info writer, temp-then-rename: truncate-opening an existing .ehviewer gets
     * ACCESS_DENIED on the reference NAS, and a partial write must not destroy the pTokens.
     */
    @Nullable
    public static OutputStream openSpiderInfoOutputStream(@NonNull GalleryInfo info) {
        try {
            SmbFile dir = SmbGalleryDirectory.getGalleryDir(info);
            final SmbFile target = new SmbFile(dir, SPIDER_INFO_FILE);
            final SmbFile temp = new SmbFile(dir, SPIDER_INFO_FILE + ".tmp");
            final OutputStream out = new java.io.BufferedOutputStream(
                    temp.getOutputStream(), SMB_IO_BUFFER);
            return new OutputStream() {
                private boolean closed;

                @Override
                public void write(int b) throws IOException {
                    out.write(b);
                }

                @Override
                public void write(@NonNull byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void flush() throws IOException {
                    out.flush();
                }

                @Override
                public void close() throws IOException {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    out.close();
                    long tPub = SystemClock.elapsedRealtime();
                    try {
                        temp.renameTo(target, true);
                        Log.i("SmbPerf", "spiderInfo.publish gid=" + info.gid + " " + (SystemClock.elapsedRealtime() - tPub) + "ms");
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to publish SMB spider_info gid=" + info.gid, e);
                        try {
                            temp.delete();
                        } catch (Throwable ignored) {
                            // Best effort; a stale temp file is harmless.
                        }
                        throw new IOException("Failed to publish spider info", e);
                    }
                }
            };
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open SMB spider_info output gid=" + info.gid, e);
            return null;
        }
    }

    @Nullable
    public static InputStream openSpiderInfoInputStream(@NonNull GalleryInfo info) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            SmbFile file = new SmbFile(SmbGalleryDirectory.resolveGalleryDir(info), SPIDER_INFO_FILE);
            if (!file.exists()) {
                Log.i("SmbPerf", "spiderInfo.read gid=" + info.gid + " missing " + (SystemClock.elapsedRealtime() - t0) + "ms");
                return null;
            }
            // SpiderInfo.read() parses byte-at-a-time; unbuffered = one round trip per byte
            // (a 28KB file measured 63s).
            InputStream in = new java.io.BufferedInputStream(file.getInputStream(), 64 * 1024);
            Log.i("SmbPerf", "spiderInfo.read gid=" + info.gid + " " + (SystemClock.elapsedRealtime() - t0) + "ms");
            return in;
        } catch (Throwable e) {
            Log.i("SmbPerf", "spiderInfo.read gid=" + info.gid + " FAILED " + (SystemClock.elapsedRealtime() - t0) + "ms: " + e);
            return null;
        }
    }

    @Nullable
    private static SmbFile findSmbImageFile(@NonNull GalleryInfo info, int index) throws IOException {
        Set<String> names = SmbGalleryDirectory.galleryFilenames(info);
        for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            String filename = SpiderDen.generateImageFilename(index, extension);
            if (names.contains(filename)) {
                // Build the single matching file reference; no per-extension exists() round-trips.
                return new SmbFile(SmbGalleryDirectory.resolveGalleryDir(info), filename);
            }
        }
        return null;
    }

    /** Drops every echo of this gallery — deletion must not leave bytes to serve (#143). */
    static void forgetGallery(long gid) {
        String prefix = gid + ":";
        synchronized (RECENT_WRITES) {
            java.util.Iterator<java.util.Map.Entry<String, byte[]>> it =
                    RECENT_WRITES.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, byte[]> e = it.next();
                if (e.getKey().startsWith(prefix)) {
                    sEchoBytes -= e.getValue().length;
                    it.remove();
                }
            }
        }
    }

    /**
     * Deletes page {@code index}'s published file — the failed-download cleanup (#140). A
     * source-side failure still publishes whatever bytes arrived (the pipe's close cannot tell
     * success from failure), so the name must go or the truncated page reads as saved forever.
     */
    public static boolean deleteImage(@NonNull GalleryInfo info, int index) {
        consumeWrite(info.gid, index);
        try {
            SmbFile file = findSmbImageFile(info, index);
            if (file == null) {
                return false;
            }
            file.delete();
            // Deletion is rare; re-listing on the next read beats patching the cache.
            SmbGalleryDirectory.invalidateListing(info.gid);
            return true;
        } catch (Throwable e) {
            SmbGalleryDirectory.invalidateListing(info.gid);
            Log.e(TAG, "Failed to delete page gid=" + info.gid + ", index=" + index, e);
            return false;
        }
    }

    /** Package-visible accessor used by {@link SmbPreviewCache} for parallel prefetch. */
    @Nullable
    static SmbFile findSmbImageFileForPreview(@NonNull GalleryInfo info, int index) {
        try {
            return findSmbImageFile(info, index);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Finds cover.<ext> by per-extension exists() probes. Do NOT switch to a listing: measured
     * slower (456→951ms for 12 covers), the cache is cold on this path.
     */
    @Nullable
    private static SmbFile findSmbCoverFile(@NonNull GalleryInfo info) throws IOException {
        long t0 = SystemClock.elapsedRealtime();
        SmbFile galleryDir = SmbGalleryDirectory.resolveGalleryDir(info);
        int probes = 0;
        for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            SmbFile file = new SmbFile(galleryDir, "cover" + extension);
            probes++;
            if (file.exists()) {
                Log.i("SmbPerf", "cover.find gid=" + info.gid + " probes=" + probes
                        + " hit=" + extension + " " + (SystemClock.elapsedRealtime() - t0)
                        + "ms thr=" + Thread.currentThread().getName());
                return file;
            }
        }
        Log.i("SmbPerf", "cover.find gid=" + info.gid + " MISSING "
                + (SystemClock.elapsedRealtime() - t0) + "ms thr="
                + Thread.currentThread().getName());
        return null;
    }

    /** One cover into memory, nowhere else — for the prefetch. */
    @Nullable
    static byte[] readCoverBytes(@NonNull GalleryInfo info) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            SmbFile file = findSmbCoverFile(info);
            if (file == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new java.io.BufferedInputStream(
                    file.getInputStream(), SMB_IO_BUFFER)) {
                IOUtils.copy(in, out);
            }
            byte[] bytes = out.toByteArray();
            Log.i("SmbPerf", "cover.fetch gid=" + info.gid + " bytes=" + bytes.length
                    + " " + (SystemClock.elapsedRealtime() - t0) + "ms thr="
                    + Thread.currentThread().getName());
            return bytes.length > 0 ? bytes : null;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read cover bytes gid=" + info.gid, e);
            return null;
        }
    }

    /** Cover as a decode shim: the native decoder needs a real fd, so stage to a temp file. */
    @Nullable
    public static InputStreamPipe openSmbCoverInputStreamPipe(@NonNull GalleryInfo info) {
        try {
            final SmbFile file = findSmbCoverFile(info);
            if (file == null) {
                return null;
            }
            return new InputStreamPipe() {
                private java.io.FileInputStream fis;
                private java.io.File tempFile;

                @Override public void obtain() {}

                @Override public void release() {}

                @Override
                public InputStream open() throws IOException {
                    if (fis != null) {
                        throw new IllegalStateException("Please close it first");
                    }
                    java.io.File dir = SmbShims.dir();
                    tempFile = java.io.File.createTempFile("smb_cover_", null, dir);
                    InputStream remote = null;
                    OutputStream local = null;
                    long tCopy = SystemClock.elapsedRealtime();
                    try {
                        remote = new java.io.BufferedInputStream(file.getInputStream(), SMB_IO_BUFFER);
                        local = new java.io.FileOutputStream(tempFile);
                        IOUtils.copy(remote, local);
                    } finally {
                        IOUtils.closeQuietly(remote);
                        IOUtils.closeQuietly(local);
                    }
                    // thr= matters: Conaco's disk executor is serial; one thread name = queueing.
                    Log.i("SmbPerf", "cover.read gid=" + info.gid + " bytes=" + tempFile.length()
                            + " " + (SystemClock.elapsedRealtime() - tCopy) + "ms thr="
                            + Thread.currentThread().getName());
                    fis = new java.io.FileInputStream(tempFile);
                    return fis;
                }

                @Override
                public void close() {
                    IOUtils.closeQuietly(fis);
                    fis = null;
                    if (tempFile != null) {
                        //noinspection ResultOfMethodCallIgnored
                        tempFile.delete();
                        tempFile = null;
                    }
                }
            };
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open SMB cover pipe gid=" + info.gid, e);
            return null;
        }
    }

    public static boolean containImage(@NonNull GalleryInfo info, int index) {
        Set<String> names = SmbGalleryDirectory.galleryFilenames(info);
        for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            if (names.contains(SpiderDen.generateImageFilename(index, extension))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Atomic write: temp name, rename on close, so no reader ever sees a half-written file (#35).
     * Two-arg renameTo because re-downloads legitimately overwrite.
     */
    @NonNull
    static OutputStream openAtomicOutputStream(@NonNull SmbFile dir, @NonNull String name,
                                               long gid) throws IOException {
        final SmbFile target = new SmbFile(dir, name);
        final SmbFile temp = new SmbFile(dir, SmbTempFiles.nameFor(name));
        final OutputStream out =
                new java.io.BufferedOutputStream(temp.getOutputStream(), SMB_IO_BUFFER);
        return new OutputStream() {
            private boolean closed;

            @Override
            public void write(int b) throws IOException {
                out.write(b);
            }

            @Override
            public void write(@NonNull byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                out.flush();
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                out.close();
                try {
                    temp.renameTo(target, true);
                    // The share just confirmed this name exists; a stale listing deleting a
                    // just-written page was #35, so the cache must learn it — incrementally
                    // (#102), not by re-listing the folder once per page.
                    SmbGalleryDirectory.noteWritten(gid, name);
                } catch (Throwable e) {
                    // Uncertain what the folder holds now — forget, do not guess.
                    SmbGalleryDirectory.invalidateListing(gid);
                    try {
                        temp.delete();
                    } catch (Throwable ignored) {
                        // Stale temporaries are skipped by readers and swept later.
                    }
                    throw new IOException("Failed to publish " + name, e);
                }
            }
        };
    }

    @Nullable
    public static OutputStreamPipe openSmbOutputStreamPipe(@NonNull GalleryInfo info, int index, @Nullable String extension) {
        try {
            String ext = extension;
            if (TextUtils.isEmpty(ext)) {
                ext = com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[0];
            }
            if (!ext.startsWith(".")) {
                ext = "." + ext;
            }
            final SmbFile finalGalleryDir = SmbGalleryDirectory.getGalleryDir(info);
            final String finalName = SpiderDen.generateImageFilename(index, ext);
            return new OutputStreamPipe() {
                private OutputStream os;
                private ByteArrayOutputStream tee;

                @Override
                public void obtain() {
                    // no-op
                }

                @Override
                public void release() {
                    // no-op
                }

                @Override
                public OutputStream open() throws IOException {
                    if (os != null) {
                        throw new IllegalStateException("Please close it first");
                    }
                    // Buffered here, not in SpiderQueen (upstream code, recurring merge conflict).
                    os = openAtomicOutputStream(finalGalleryDir, finalName, info.gid);
                    tee = new ByteArrayOutputStream();
                    return new java.io.FilterOutputStream(os) {
                        @Override
                        public void write(int b) throws IOException {
                            out.write(b);
                            if (tee != null) {
                                tee.write(b);
                            }
                        }

                        @Override
                        public void write(@NonNull byte[] b, int off, int len) throws IOException {
                            out.write(b, off, len);
                            if (tee != null) {
                                tee.write(b, off, len);
                            }
                        }
                    };
                }

                @Override
                public void close() {
                    OutputStream stream = os;
                    ByteArrayOutputStream copy = tee;
                    os = null;
                    tee = null;
                    if (stream == null) {
                        return;
                    }
                    try {
                        // The atomic publish happens inside close(); only a published page may echo.
                        stream.close();
                        if (copy != null && copy.size() > 0) {
                            rememberWrite(info.gid, index, copy.toByteArray());
                        }
                    } catch (IOException e) {
                        Log.w(TAG, "SMB page publish failed gid=" + info.gid + " idx=" + index, e);
                    }
                }
            };
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open SMB output pipe gid=" + info.gid + ", index=" + index, e);
            return null;
        }
    }

    @Nullable
    public static InputStreamPipe openSmbInputStreamPipe(@NonNull GalleryInfo info, int index) {
        // The read straight after the write (#138): answered from the one-shot echo, in memory,
        // instead of pulling the page back off the NAS it is still being written to. Consumed
        // here, so every later read takes the real path below.
        final byte[] echo = consumeWrite(info.gid, index);
        if (echo != null) {
            Log.i("SmbPerf", "echo idx=" + index + " gid=" + info.gid + " bytes=" + echo.length);
            return new InputStreamPipe() {
                @Override public void obtain() {}

                @Override public void release() {}

                @Override
                public InputStream open() {
                    return new java.io.ByteArrayInputStream(echo);
                }

                @Override public void close() {}
            };
        }
        try {
            final SmbFile file = findSmbImageFile(info, index);
            if (file == null) {
                return null;
            }
            // The native decoder needs a real fd; materialize to a temp file that dies with the pipe.
            return new InputStreamPipe() {
                private java.io.FileInputStream fis;
                private java.io.File tempFile;

                @Override
                public void obtain() {
                    // no-op
                }

                @Override
                public void release() {
                    // no-op
                }

                @Override
                public InputStream open() throws IOException {
                    if (fis != null) {
                        throw new IllegalStateException("Please close it first");
                    }
                    java.io.File dir = SmbShims.dir();
                    tempFile = java.io.File.createTempFile("smb_img_", null, dir);
                    InputStream remote = null;
                    OutputStream local = null;
                    long t0 = SystemClock.elapsedRealtime();
                    long tOpen;
                    try {
                        remote = new java.io.BufferedInputStream(file.getInputStream(), SMB_IO_BUFFER);
                        tOpen = SystemClock.elapsedRealtime();
                        local = new java.io.FileOutputStream(tempFile);
                        IOUtils.copy(remote, local);
                    } finally {
                        IOUtils.closeQuietly(remote);
                        IOUtils.closeQuietly(local);
                    }
                    Log.i("SmbPerf", "materialize idx=" + index + " bytes=" + tempFile.length()
                            + " open=" + (tOpen - t0) + "ms copy=" + (SystemClock.elapsedRealtime() - tOpen) + "ms thr=" + Thread.currentThread().getName());
                    fis = new java.io.FileInputStream(tempFile);
                    return fis;
                }

                @Override
                public void close() {
                    IOUtils.closeQuietly(fis);
                    fis = null;
                    if (tempFile != null) {
                        //noinspection ResultOfMethodCallIgnored
                        tempFile.delete();
                        tempFile = null;
                    }
                }
            };
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open SMB input pipe gid=" + info.gid + ", index=" + index, e);
            return null;
        }
    }

    /** Whole stream as UTF-8, byte-exact (readLine() dropped terminators and corrupted files). */
    static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[SMB_IO_BUFFER];
        int read;
        while ((read = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }
}
