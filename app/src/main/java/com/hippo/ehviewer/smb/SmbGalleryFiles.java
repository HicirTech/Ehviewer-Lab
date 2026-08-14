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
 * The bytes of a gallery on the share: pages, covers, spider info (#97).
 *
 * <p>Everything here opens, finds, counts or removes the <em>files inside</em> one gallery's
 * folder — the folder itself is {@link SmbGalleryDirectory}'s business, and what happens to the
 * gallery as a whole (delete, rename, finalize) is {@link SmbGalleryLifecycle}'s. Two rules this
 * class enforces for every writer and reader in the app:
 *
 * <ul>
 *   <li><b>No reader ever sees a half-written file.</b> All writes go through
 *       {@link #openAtomicOutputStream}: temp name, rename on close. That is the #35 fix.</li>
 *   <li><b>Local bytes are decode shims, not copies.</b> The native decoder needs a real file
 *       descriptor, so reads materialise into {@code cache/smb_tmp} and the shim dies with the
 *       pipe — the share stays the only durable copy anywhere.</li>
 * </ul>
 *
 * <p>Split out of the old 1494-line {@code SmbStorage}; method bodies are verbatim from there.
 */
public final class SmbGalleryFiles {

    private static final String TAG = "SmbStorage";

    private static final String SPIDER_INFO_FILE = ".ehviewer";

    /**
     * Buffer every SMB stream through this much, because jcifs turns each caller
     * {@code read(byte[])} / {@code write(byte[])} into its own SMB2 request. The array the caller
     * happens to pass therefore sets the on-the-wire request size, and a small one splits a
     * transfer into hundreds of serialized round trips.
     *
     * <p>Measured on a real share over WiFi with a 4 MB file (see
     * {@code ai-workspace/spike/jcifs-write-semantics.md}): writing in 4 KB chunks — which is
     * exactly what {@code SpiderQueen} does on the download path — manages 0.5 MB/s, while 256 KB
     * reaches 6.3 MB/s. Reads plateau by 64 KB at 6.8 MB/s. One value covers both.
     *
     * <p>Note that jcifs' own {@code rcv_buf_size} does <em>not</em> help: raising it to 1 MB while
     * still reading through a {@code byte[8192]} left throughput at 1.3 MB/s. The request size
     * follows the caller's array, not the configuration.
     */
    static final int SMB_IO_BUFFER = 256 * 1024;

    private SmbGalleryFiles() {}

    /**
     * Opens the spider-info file for writing.
     *
     * <p>Writes to a sibling temp file and renames it over the target on close instead of
     * truncating the target in place. A truncate-open of an existing {@code .ehviewer} can be
     * refused by the server with ACCESS_DENIED while creating a new file in the same folder and
     * renaming it over the target both succeed — observed against the reference NAS, and it
     * applies even to an {@code .ehviewer} just created there, so it is not an ownership artefact
     * of how the gallery arrived. Without this, every attempt to persist reading progress failed
     * and the failure was only visible in the log.
     *
     * <p>The rename is also the safer shape in its own right: a failed or partial write leaves the
     * previous spider info untouched rather than truncating it, and losing that file costs the
     * gallery its pTokens.
     */
    @Nullable
    public static OutputStream openSpiderInfoOutputStream(@NonNull GalleryInfo info) {
        try {
            SmbFile dir = SmbGalleryDirectory.getGalleryDir(info);
            final SmbFile target = new SmbFile(dir, SPIDER_INFO_FILE);
            final SmbFile temp = new SmbFile(dir, SPIDER_INFO_FILE + ".tmp");
            // Buffered: the pTokens of a large gallery run to tens of KB and the caller writes
            // them in small pieces, which would otherwise be one SMB2 WRITE apiece.
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
            // SpiderInfo.read() parses this stream one byte at a time (IOUtils.readAsciiLine).
            // Unbuffered, every byte is its own SMB READ round trip: a 924-page .ehviewer
            // (~28KB) measured 63 seconds to parse. Buffering turns that into one round trip.
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
     * Locates the {@code cover.<ext>} file written by
     * {@link SmbGalleryLifecycle#finalizeDownloadedGallery}. We try every image extension the rest
     * of the stack supports, since cover's extension depends on the upstream Content-Type at the
     * time of save.
     */
    @Nullable
    private static SmbFile findSmbCoverFile(@NonNull GalleryInfo info) throws IOException {
        long t0 = SystemClock.elapsedRealtime();
        // Asks the share about each candidate name, and does NOT go through galleryFilenames()
        // the way findSmbImageFile and its neighbours do. That was tried, on the reasoning that
        // one listing must beat five existence checks, and measured: twelve covers went from
        // 456 ms of probing to 951 ms. A gallery folder holds every page as well as the cover, so
        // listing it returns far more than the five targeted questions do, and on this path the
        // listing cache is cold — the inventory has no reason to have populated it.
        //
        // The probes are cheap. What is expensive is that there are five of them per cover and
        // that every cover on a real share is .webp, the last entry in the extension list.
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

    /**
     * One gallery's cover, read off the share into memory.
     *
     * <p>For the prefetch: bytes land in RAM and nowhere else, so the share stays the only durable
     * copy anywhere. The buffered read matters for the usual reason — jcifs sizes its on-the-wire
     * requests from the caller's array.
     */
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

    /**
     * Stage the on-share cover to a local temp file and return a {@link java.io.FileInputStream}-backed
     * pipe. Conaco's image decoder requires a real file descriptor (same constraint as page
     * loads), so SmbFileInputStream cannot be returned directly.
     */
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
                    java.io.File dir = new java.io.File(
                            EhApplication.getInstance().getCacheDir(), "smb_tmp");
                    if (!dir.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    }
                    tempFile = java.io.File.createTempFile("smb_cover_", null, dir);
                    InputStream remote = null;
                    OutputStream local = null;
                    long tCopy = SystemClock.elapsedRealtime();
                    try {
                        // Buffered so the 16KB copy loop drains a 256KB prefetch instead of
                        // issuing one small SMB READ per chunk.
                        remote = new java.io.BufferedInputStream(file.getInputStream(), SMB_IO_BUFFER);
                        local = new java.io.FileOutputStream(tempFile);
                        IOUtils.copy(remote, local);
                    } finally {
                        IOUtils.closeQuietly(remote);
                        IOUtils.closeQuietly(local);
                    }
                    // The thread name is half the point: covers are loaded through Conaco, whose
                    // disk executor is serial, so seeing every one of these on the same thread is
                    // what tells you they are queueing behind each other rather than overlapping.
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

    public static boolean removeImage(@NonNull GalleryInfo info, int index) {
        boolean result = false;
        try {
            Set<String> names = SmbGalleryDirectory.galleryFilenames(info);
            SmbFile galleryDir = null;
            for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                String filename = SpiderDen.generateImageFilename(index, extension);
                if (!names.contains(filename)) {
                    continue;
                }
                if (galleryDir == null) {
                    galleryDir = SmbGalleryDirectory.resolveGalleryDir(info);
                }
                SmbFile file = new SmbFile(galleryDir, filename);
                if (file.exists()) {
                    file.delete();
                    result = true;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to remove SMB image gid=" + info.gid + ", index=" + index, e);
        } finally {
            SmbGalleryDirectory.invalidateListing(info.gid);
        }
        return result;
    }

    /**
     * Opens a file on the share for writing so that no reader can ever see a half-written one.
     *
     * <p>Bytes go to a temporary name and are renamed onto the target when the stream closes. SMB
     * has no other way to make a write look instantaneous: a file created under its final name
     * appears in a directory listing as soon as it exists, and every reader here decides a page,
     * cover or preview is available by finding its name. So for as long as a write is in flight,
     * anybody looking sees a file that is present and incomplete — reads a truncated image, or
     * fails outright — and a moment later the same read succeeds. That is #35.
     *
     * <p>Spider info has been written this way from the start. Nothing else was, which is why the
     * symptom was never confined to reading pages: covers and previews come off the same share by
     * the same rule.
     *
     * <p>Two-argument {@code renameTo}: the one-argument form refuses an existing target, and
     * these do overwrite — a re-downloaded page, a refreshed cover.
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
                    // The listing this gallery is looked up through is cached for a few seconds
                    // and was taken before this file existed. Leaving it is what #35 actually
                    // was: the downloader writes a page, immediately reads it back to check it,
                    // is told by the stale listing that it is not there, calls that a failed
                    // page -- and deletes the file it just wrote. The reader shares the page
                    // state, so it shows "Reading Failed" for a page that was fine.
                    SmbGalleryDirectory.invalidateListing(gid);
                } catch (Throwable e) {
                    try {
                        temp.delete();
                    } catch (Throwable ignored) {
                        // A stale temporary is skipped by every reader and cleaned up later.
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
                    // SpiderQueen pumps the downloaded page through a byte[4096] (SpiderQueen:1482),
                    // and that array would otherwise become the SMB2 WRITE size — 0.5 MB/s where the
                    // link does 6.4. Buffering here rather than widening SpiderQueen's array keeps
                    // the fix inside the smb package: SpiderQueen is upstream code and already the
                    // recurring conflict point on every upstream merge.
                    os = openAtomicOutputStream(finalGalleryDir, finalName, info.gid);
                    return os;
                }

                @Override
                public void close() {
                    IOUtils.closeQuietly(os);
                    os = null;
                }
            };
        } catch (Throwable e) {
            Log.e(TAG, "Failed to open SMB output pipe gid=" + info.gid + ", index=" + index, e);
            return null;
        }
    }

    @Nullable
    public static InputStreamPipe openSmbInputStreamPipe(@NonNull GalleryInfo info, int index) {
        try {
            final SmbFile file = findSmbImageFile(info, index);
            if (file == null) {
                return null;
            }
            // SpiderDecoder casts the InputStream to FileInputStream and Image.decode requires
            // a real file descriptor for the native decoder. SmbFileInputStream is NOT a
            // FileInputStream, so we materialize the SMB content into a local temp file and
            // hand back a FileInputStream over that temp file. The temp file is removed when
            // the pipe is closed.
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
                    java.io.File dir = new java.io.File(
                            EhApplication.getInstance().getCacheDir(), "smb_tmp");
                    if (!dir.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        dir.mkdirs();
                    }
                    tempFile = java.io.File.createTempFile("smb_img_", null, dir);
                    InputStream remote = null;
                    OutputStream local = null;
                    long t0 = SystemClock.elapsedRealtime();
                    long tOpen;
                    try {
                        // Buffered for the same reason as the cover path above.
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

    static String readAll(InputStream is) throws IOException {
        // Byte-buffered read so JSON files round-trip unchanged. The previous readLine()
        // loop silently dropped every line terminator, which is harmless for single-line
        // JSON but corrupts pretty-printed metadata blobs and any future caller that
        // expects the file's exact contents.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[SMB_IO_BUFFER];
        int read;
        while ((read = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }
}
