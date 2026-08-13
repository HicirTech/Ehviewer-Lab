package com.hippo.ehviewer.smb;

import android.content.Context;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSONObject;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public final class SmbStorage {

    private static final String TAG = "SmbStorage";
    // Package-private: SmbMetadata reads/writes the same metadata.json.
    static final String METADATA_FILE = "metadata.json";
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

    /**
     * Per-gid intent mark for routing reads/writes to SMB. Replaces the old global
     * {@code Settings.getSmbSaveEnabled()} routing flag — that was leaking phone downloads
     * onto the SMB share whenever the master toggle was on. Now only galleries explicitly
     * marked here (by {@link SmbDirectDownloader} or {@code LocalInventoryScene.openReader})
     * use SMB I/O. Regular DownloadManager downloads always go to phone storage.
     */
    private static final java.util.Set<Long> SMB_TARGET_GIDS =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private SmbStorage() {}

    public static void markGidAsSmbTarget(long gid) {
        SMB_TARGET_GIDS.add(gid);
    }

    public static void unmarkGidAsSmbTarget(long gid) {
        SMB_TARGET_GIDS.remove(gid);
    }

    public static boolean isGidMarkedSmbTarget(long gid) {
        return SMB_TARGET_GIDS.contains(gid);
    }

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(Settings.getSmbHost()) &&
                !TextUtils.isEmpty(Settings.getSmbShareName());
    }

    // Package-private: SmbDownloadStateStore connects to the same share the same way.
    @NonNull
    static CIFSContext buildContext() {
        CIFSContext base = baseContext();
        String username = Settings.getSmbUsername();
        if (TextUtils.isEmpty(username)) {
            return base;
        }
        NtlmPasswordAuthenticator authenticator =
                new NtlmPasswordAuthenticator(null, username, Settings.getSmbPassword());
        return base.withCredentials(authenticator);
    }

    // Cached base CIFS context. The default ("auto") path reuses jcifs' SingletonContext so its
    // connection pool stays shared; the "signing disabled" path needs custom config, so it gets its
    // own pooled BaseContext built from a PropertyConfiguration. Rebuilt only when the setting flips.
    private static volatile CIFSContext sBaseContext;
    private static volatile boolean sBaseSigningDisabled;

    @NonNull
    private static CIFSContext baseContext() {
        boolean signingDisabled = Settings.getSmbSigningDisabled();
        CIFSContext base = sBaseContext;
        if (base != null && sBaseSigningDisabled == signingDisabled) {
            return base;
        }
        synchronized (SmbStorage.class) {
            if (sBaseContext == null || sBaseSigningDisabled != signingDisabled) {
                sBaseContext = signingDisabled ? buildNoSigningContext() : SingletonContext.getInstance();
                sBaseSigningDisabled = signingDisabled;
            }
            return sBaseContext;
        }
    }

    @NonNull
    private static CIFSContext buildNoSigningContext() {
        try {
            Properties props = new Properties();
            // jcifs already defaults signingPreferred/signingEnforced to false; setting them keeps
            // that explicit. ipcSigningEnforced defaults to TRUE, so turning it off is the real change
            // — it drops the per-packet HMAC on the control/IPC traffic that signing would otherwise
            // add. Data-share signing beyond this is governed by what the server requires.
            props.setProperty("jcifs.smb.client.signingPreferred", "false");
            props.setProperty("jcifs.smb.client.signingEnforced", "false");
            props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
            return new BaseContext(new PropertyConfiguration(props));
        } catch (Throwable e) {
            // CIFSException (or anything) building the custom config: fall back to the default context
            // rather than break SMB entirely.
            Log.e(TAG, "Failed to build no-signing CIFS context; using default", e);
            return SingletonContext.getInstance();
        }
    }

    /**
     * The share URL galleries live under. {@link #buildSmbUrl} is the configured share path itself,
     * which now holds {@code download/} alongside whatever else the share needs to carry — the
     * per-client download state (#59) and the gallery index (#16) are siblings of the galleries,
     * not entries among them.
     */
    @NonNull
    private static String galleryRootUrl() {
        return SmbPaths.buildGalleryRootUrl(buildSmbUrl());
    }

    /**
     * The configured share path itself. Connectivity checks and directory setup use it directly;
     * everything else goes through {@link #galleryRootUrl()} or {@code SmbPaths.buildStateRootUrl}.
     */
    @NonNull
    static String buildSmbUrl() {
        return SmbPaths.buildShareUrl(
                Settings.getSmbHost(),
                Settings.getSmbPort(),
                Settings.getSmbShareName(),
                Settings.getSmbSharePath());
    }

    /**
     * Builds a lightweight {@link GalleryInfo} carrying just the fields needed by SMB
     * lookup helpers ({@code gid} + {@code title}). Used from contexts that must avoid
     * holding a back-reference to a full GalleryInfo (e.g. parcelable preview sets).
     */
    @NonNull
    public static GalleryInfo lookupKey(long gid, @Nullable String title) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.title = title;
        return info;
    }

    // Package-private: SmbMetadata resolves the same per-gallery dir for its writes.
    @NonNull
    static SmbFile getGalleryDir(@NonNull GalleryInfo info) throws IOException {
        long t0 = SystemClock.elapsedRealtime();
        CIFSContext cifs = buildContext();
        SmbFile galleryRoot = new SmbFile(galleryRootUrl(), cifs);
        if (!galleryRoot.exists()) {
            galleryRoot.mkdirs();
        }
        SmbFile galleryDir = new SmbFile(galleryRoot, SmbPaths.buildGalleryFolderName(info) + "/");
        if (!galleryDir.exists()) {
            galleryDir.mkdirs();
        }
        Log.i("SmbPerf", "getGalleryDir gid=" + info.gid + " " + (SystemClock.elapsedRealtime() - t0) + "ms thr=" + Thread.currentThread().getName());
        return galleryDir;
    }

    /**
     * Resolves the per-gallery SmbFile reference WITHOUT touching the share (no {@code exists()},
     * no {@code mkdirs()}). {@link #getGalleryDir} did two existence round-trips (plus a possible
     * mkdirs) on every call, which is pure waste on the read path where the folder already exists —
     * and that cost was paid once per page, per existence probe. Read-only callers use this.
     *
     * <p>Waste is not the only reason. A query that creates a directory leaves one behind whenever
     * the answer turns out to be "no and nothing follows" — an empty {@code <gid>-<title>/} that
     * the inventory then counts towards its page total while rendering nothing for it. That is how
     * this was found: the cross-client claim check in #59 returns after the completeness check, and
     * every blocked enqueue littered the share.
     */
    @NonNull
    /**
     * The gallery's folder, without creating it.
     *
     * <p>Unlike {@link #getGalleryDir}, which exists for writers and so calls {@code mkdirs}, this
     * answers for callers that need to know whether a gallery is on the share at all — asking with
     * {@code getGalleryDir} would make the answer yes, and leave an empty folder behind that Local
     * Inventory then lists as a gallery with no pages.
     *
     * <p>Package-private for {@link SmbMetadata}, which re-syncs an existing gallery's record.
     */
    static SmbFile resolveGalleryDir(@NonNull GalleryInfo info) throws IOException {
        CIFSContext cifs = buildContext();
        SmbFile galleryRoot = new SmbFile(galleryRootUrl(), cifs);
        return new SmbFile(galleryRoot, SmbPaths.buildGalleryFolderName(info) + "/");
    }

    /**
     * Short-lived per-gid snapshot of the gallery folder's file names. {@link #containImage} and
     * {@link #findSmbImageFile} answer "is page N saved?" from this in-memory set instead of doing
     * a {@code getGalleryDir()} + one {@code exists()} per supported extension — i.e. ~7 SMB
     * round-trips — on every single page. That per-page cost is what made opening / scanning a big
     * gallery crawl. One {@code list()} now serves every page check until the TTL lapses or a
     * structural change ({@link #prepareGalleryDir}, {@link #removeImage},
     * {@link #deleteGalleryFolder}, {@link #finalizeDownloadedGallery}) invalidates it.
     */
    private static final GalleryListingCache LISTING_CACHE =
            new GalleryListingCache(GalleryListingCache.DEFAULT_TTL_MS);

    @NonNull
    private static Set<String> galleryFilenames(@NonNull GalleryInfo info) {
        long now = SystemClock.elapsedRealtime();
        Set<String> cached = LISTING_CACHE.get(info.gid, now);
        if (cached != null) {
            return cached;
        }
        Set<String> names = new HashSet<>();
        long tList = SystemClock.elapsedRealtime();
        try {
            String[] list = resolveGalleryDir(info).list();
            if (list != null) {
                Collections.addAll(names, list);
            }
            Log.i("SmbPerf", "list gid=" + info.gid + " n=" + names.size() + " " + (SystemClock.elapsedRealtime() - tList) + "ms thr=" + Thread.currentThread().getName());
        } catch (Throwable e) {
            // Folder may not exist yet (gallery not saved) — treat as empty, cache the miss so we
            // don't re-probe a missing dir on every page.
        }
        LISTING_CACHE.put(info.gid, names, now);
        return names;
    }

    private static void invalidateListing(long gid) {
        LISTING_CACHE.invalidate(gid);
    }

    public static boolean prepareGalleryDir(@NonNull GalleryInfo info) {
        try {
            getGalleryDir(info);
            invalidateListing(info.gid);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to prepare SMB gallery dir gid=" + info.gid, e);
            return false;
        }
    }

    /**
     * Recursively deletes the on-share gallery folder. Used when a SMB download task is
     * cancelled — leaving partial pages behind would clutter the share and confuse a later
     * resume / re-enqueue (since {@link #isGalleryComplete} could count stale pages).
     * Returns true if the folder was deleted or never existed.
     */
    public static boolean deleteGalleryFolder(@NonNull GalleryInfo info) {
        try {
            // Build the directory reference without auto-creating it (getGalleryDir would
            // mkdirs() on a missing dir, then we'd immediately try to delete what we just
            // created — wasteful at best, wrong at worst if the dir never existed).
            SmbFile galleryDir = resolveGalleryDir(info);
            if (!galleryDir.exists()) {
                return true;
            }
            // jcifs-ng's SmbFile.delete() throws SmbException (STATUS_DIRECTORY_NOT_EMPTY)
            // when the directory is non-empty. Delete all contents first, then the dir.
            deleteSmbDirRecursive(galleryDir);
            return !galleryDir.exists();
        } catch (Throwable e) {
            Log.w(TAG, "Failed to delete SMB gallery folder gid=" + info.gid, e);
            return false;
        } finally {
            invalidateListing(info.gid);
        }
    }

    /**
     * Recursively deletes {@code dir} and all of its contents on the SMB share.
     * jcifs-ng requires a directory to be empty before {@link SmbFile#delete()} succeeds,
     * so we traverse depth-first and delete files before their parent directories.
     * <p>
     * Deletion is best-effort: individual child failures are logged and skipped so that
     * remaining siblings are still processed. The parent directory delete at the end will
     * propagate any {@link IOException} if not all children were removed.
     */
    private static void deleteSmbDirRecursive(@NonNull SmbFile dir) throws IOException {
        SmbFile[] children = dir.listFiles();
        if (children != null) {
            for (SmbFile child : children) {
                try {
                    if (child.isDirectory()) {
                        deleteSmbDirRecursive(child);
                    } else {
                        child.delete();
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Could not delete SMB entry: " + child.getPath(), e);
                }
            }
        }
        dir.delete();
    }

    /**
     * Returns true when the on-share copy has a metadata.json declaring a positive
     * page count and the same number of image files are present on the share. Used by
     * the SMB download path to skip galleries that are already fully saved.
     * Performs SMB I/O — must be called from a worker thread.
     */
    public static boolean isGalleryComplete(@NonNull GalleryInfo info) {
        long tPerf = SystemClock.elapsedRealtime();
        try {
            SmbFile galleryDir = resolveGalleryDir(info);
            SmbFile metadata = new SmbFile(galleryDir, METADATA_FILE);
            if (!metadata.exists()) {
                return false;
            }
            int declaredPages = info.pages;
            try (InputStream is = metadata.getInputStream()) {
                String json = readAll(is);
                JSONObject obj = JSONObject.parseObject(json);
                if (obj != null) {
                    Integer p = obj.getInteger("pages");
                    if (p != null && p > 0) {
                        declaredPages = p;
                    }
                }
            } catch (Throwable ignored) {
            }
            if (declaredPages <= 0) {
                int spiderPages = readPagesFromSpiderInfo(info);
                if (spiderPages > 0) declaredPages = spiderPages;
            }
            if (declaredPages <= 0) {
                return false;
            }
            int saved = countSavedImages(galleryDir, declaredPages);
            Log.i("SmbPerf", "isGalleryComplete gid=" + info.gid + " saved=" + saved + "/" + declaredPages
                    + " " + (SystemClock.elapsedRealtime() - tPerf) + "ms thr=" + Thread.currentThread().getName());
            return saved >= declaredPages;
        } catch (Throwable e) {
            // A transient failure here (congested transport, timeout) must be visible: returning
            // false silently turns into a full re-download of a complete gallery.
            Log.w("SmbPerf", "isGalleryComplete gid=" + info.gid + " EXCEPTION after "
                    + (SystemClock.elapsedRealtime() - tPerf) + "ms: " + e);
            return false;
        }
    }

    /**
     * Counts pages that have at least one image file in the gallery folder. Uses a single
     * {@code listFiles()} call to avoid N×{extensions} SMB round-trips (which previously
     * caused OOMs on large galleries because each round-trip allocated jcifs buffers).
     */
    private static int countSavedImages(@NonNull SmbFile galleryDir, int pageCount) throws IOException {
        String[] names = galleryDir.list();
        if (names == null || names.length == 0) {
            return 0;
        }
        java.util.HashSet<String> present = new java.util.HashSet<>(names.length * 2);
        java.util.Collections.addAll(present, names);
        int count = 0;
        for (int i = 0; i < pageCount; i++) {
            for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                if (present.contains(SpiderDen.generateImageFilename(i, extension))) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

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
            SmbFile dir = getGalleryDir(info);
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
            SmbFile file = new SmbFile(resolveGalleryDir(info), SPIDER_INFO_FILE);
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
        Set<String> names = galleryFilenames(info);
        for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            String filename = SpiderDen.generateImageFilename(index, extension);
            if (names.contains(filename)) {
                // Build the single matching file reference; no per-extension exists() round-trips.
                return new SmbFile(resolveGalleryDir(info), filename);
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
     * Locates the {@code cover.<ext>} file written by {@link #downloadAndWriteCover}. We try
     * every image extension the rest of the stack supports, since cover's extension depends
     * on the upstream Content-Type at the time of save.
     */
    @Nullable
    private static SmbFile findSmbCoverFile(@NonNull GalleryInfo info) throws IOException {
        SmbFile galleryDir = resolveGalleryDir(info);
        for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            SmbFile file = new SmbFile(galleryDir, "cover" + extension);
            if (file.exists()) {
                return file;
            }
        }
        return null;
    }

    /**
     * Stage the on-share cover to a local temp file and return a {@link FileInputStream}-backed
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
        Set<String> names = galleryFilenames(info);
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
            Set<String> names = galleryFilenames(info);
            SmbFile galleryDir = null;
            for (String extension : com.hippo.ehviewer.gallery.GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
                String filename = SpiderDen.generateImageFilename(index, extension);
                if (!names.contains(filename)) {
                    continue;
                }
                if (galleryDir == null) {
                    galleryDir = resolveGalleryDir(info);
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
            invalidateListing(info.gid);
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
                    invalidateListing(gid);
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
            final SmbFile finalGalleryDir = getGalleryDir(info);
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

    /**
     * Verifies the configured share is reachable, and sets up the directory galleries live in.
     *
     * <p>Checks the share path itself, not {@code download/} — the share being reachable is the
     * thing being tested, and {@code download/} legitimately does not exist until something
     * creates it.
     *
     * <p>This is also where that directory gets created, because pressing "test connection" is
     * when the user finishes configuring the share, and a write problem is far more useful
     * reported here than at the first download. Creation failing is not a connection failure
     * though: a read-only share browses perfectly well, so it comes back as a warning rather than
     * an exception.
     *
     * @return {@code null} when everything is in place, otherwise a user-facing warning to show
     *         alongside the success message.
     */
    @Nullable
    public static String testConnection() throws IOException {
        String host = Settings.getSmbHost();
        String shareName = Settings.getSmbShareName();

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(shareName)) {
            // User-facing — surfaced through the Settings toast.
            throw new IOException(EhApplication.getInstance()
                    .getString(R.string.smb_test_error_unconfigured));
        }

        CIFSContext cifs = buildContext();
        SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
        if (!shareRoot.exists()) {
            throw new IOException(EhApplication.getInstance()
                    .getString(R.string.smb_test_error_share_not_accessible));
        }

        try {
            SmbFile galleryRoot = new SmbFile(galleryRootUrl(), cifs);
            if (!galleryRoot.exists()) {
                galleryRoot.mkdirs();
            }
        } catch (Throwable e) {
            Log.w(TAG, "Share is reachable but " + SmbPaths.GALLERY_DIR + "/ could not be created", e);
            return EhApplication.getInstance()
                    .getString(R.string.smb_test_warn_gallery_dir, SmbPaths.GALLERY_DIR);
        }
        return null;
    }

    /**
     * How long to keep trying to rename a gallery folder before giving up.
     *
     * <p>The failure being retried is another device holding a file inside the folder open, which
     * the server answers with {@code 0xc0000022} and which clears the moment the handle does. The
     * spike measured a writer through in 13 attempts over 1918 ms against a reader that held on for
     * 1.5 s, so this leaves room for a considerably slower one. Giving up is not serious: the
     * gallery keeps the name it has.
     */
    private static final long RENAME_DEADLINE_MS = 8_000L;
    private static final long RENAME_BACKOFF_START_MS = 100L;
    private static final long RENAME_BACKOFF_MAX_MS = 800L;

    /**
     * Renames a gallery's folder so it matches a new title (#86).
     *
     * <p>The folder is named {@code <gid>-<title>} and the path is built back out of the record, so
     * the two have to move together. Doing this first and writing the record second is not a
     * detail: the intermediate state it avoids -- a record naming a folder that does not exist --
     * is one where {@code getGalleryDir} creates an empty one and the gallery disappears from
     * Local Inventory behind it.
     *
     * <p>Refuses rather than merges when something already occupies the new name. Two galleries
     * with the same gid cannot both be right, and picking one would throw the other away.
     *
     * <p>Performs SMB I/O; call from a worker thread.
     *
     * @return whether the folder now carries the new name, including when it already did
     */
    public static boolean renameGalleryFolder(@NonNull GalleryInfo info, @Nullable String newTitle) {
        String from = SmbPaths.buildGalleryFolderName(info);
        String to = SmbPaths.buildGalleryFolderName(info.gid, newTitle);
        if (from.equals(to)) {
            return true;
        }
        try {
            CIFSContext cifs = buildContext();
            SmbFile galleryRoot = new SmbFile(galleryRootUrl(), cifs);
            SmbFile source = new SmbFile(galleryRoot, from + "/");
            if (!source.exists()) {
                Log.w(TAG, "Cannot rename gid=" + info.gid + ": " + from + " is not on the share");
                return false;
            }
            SmbFile target = new SmbFile(galleryRoot, to + "/");
            if (target.exists()) {
                Log.w(TAG, "Cannot rename gid=" + info.gid + ": " + to + " already exists");
                return false;
            }

            long deadline = System.currentTimeMillis() + RENAME_DEADLINE_MS;
            long backoff = RENAME_BACKOFF_START_MS;
            Throwable last = null;
            while (true) {
                try {
                    // Single-argument on purpose: the two-argument form replaces the target, and
                    // there is nothing here worth replacing that we would not rather refuse.
                    source.renameTo(target);
                    invalidateListing(info.gid);
                    Log.i(TAG, "Renamed gid=" + info.gid + " to " + to);
                    return true;
                } catch (Throwable e) {
                    last = e;
                    if (System.currentTimeMillis() + backoff >= deadline) {
                        break;
                    }
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoff = Math.min(backoff * 2, RENAME_BACKOFF_MAX_MS);
                }
            }
            Log.w(TAG, "Gave up renaming gid=" + info.gid + " after " + RENAME_DEADLINE_MS + "ms", last);
            return false;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to rename gid=" + info.gid, e);
            return false;
        }
    }

    public static void finalizeDownloadedGallery(@NonNull Context context, @NonNull GalleryInfo info) {
        try {
            SmbFile galleryDir = getGalleryDir(info);
            // Resolve the real page count if the caller didn't already have it (e.g. info came
            // from a search list). We deliberately do NOT mutate `info.pages` here — the same
            // GalleryInfo instance is held by SmbDirectDownloader.active and can be observed
            // concurrently from the main thread (task snapshots, notifications). Passing the
            // resolved value down keeps the write thread-local.
            int resolvedPages = info.pages;
            if (resolvedPages <= 0) {
                int spiderPages = readPagesFromSpiderInfo(info);
                if (spiderPages > 0) {
                    resolvedPages = spiderPages;
                }
            }
            SmbMetadata.writeMetadataWithDetail(context, galleryDir, info, resolvedPages);
            downloadAndWriteCover(context, galleryDir, info);
            // The one moment this folder is both certainly ours and worth a listing: an earlier
            // run of this download that was killed mid-page left its temporaries here, and nothing
            // else will ever look (#75).
            SmbTempFiles.sweep(galleryDir, System.currentTimeMillis());
        } catch (Throwable e) {
            Log.e(TAG, "Failed to finalize SMB gallery gid=" + info.gid, e);
        } finally {
            // The download just wrote every page; drop the stale listing so a reader opening this
            // gallery right after sees the saved files instead of a pre-download empty snapshot.
            invalidateListing(info.gid);
        }
    }

    private static int readPagesFromSpiderInfo(@NonNull GalleryInfo info) {
        InputStream is = openSpiderInfoInputStream(info);
        if (is == null) {
            return 0;
        }
        try {
            com.hippo.ehviewer.spider.SpiderInfo spiderInfo = com.hippo.ehviewer.spider.SpiderInfo.read(is);
            return spiderInfo != null ? spiderInfo.pages : 0;
        } catch (Throwable e) {
            return 0;
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    private static void downloadAndWriteCover(@NonNull Context context, @NonNull SmbFile galleryDir, @NonNull GalleryInfo info) {
        if (TextUtils.isEmpty(info.thumb)) {
            return;
        }
        String thumbUrl = info.thumb;
        if (thumbUrl.startsWith("//")) {
            thumbUrl = "https:" + thumbUrl;
        }
        OkHttpClient client = EhApplication.getOkHttpClient(context);
        Request request = new Request.Builder().url(thumbUrl).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return;
            }
            String extension = ".jpg";
            String contentType = response.body().contentType() != null ? response.body().contentType().toString() : null;
            if (contentType != null) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType);
                if (!TextUtils.isEmpty(ext)) {
                    extension = "." + ext;
                }
            }
            // Open source first; the response body's byteStream is owned by the response
            // (closed via try-with-resources) so we just need to make sure the SMB output
            // open failing doesn't drop a still-uncopied body on the floor.
            InputStream in = response.body().byteStream();
            OutputStream out;
            try {
                out = openAtomicOutputStream(galleryDir, "cover" + extension, info.gid);
            } catch (IOException e) {
                IOUtils.closeQuietly(in);
                throw e;
            }
            copyStream(in, out);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to download cover", e);
        }
    }

    @NonNull
    public static List<GalleryInfo> loadInventory() {
        return loadInventory(SmbSortMode.DOWNLOAD_DATE_DESC);
    }

    @NonNull
    public static List<GalleryInfo> loadInventory(@NonNull SmbSortMode mode) {
        if (!isConfigured()) {
            return new ArrayList<>();
        }

        // The expensive half of the inventory, and until now the unmeasured one: every sort other
        // than "recently downloaded" needs fields that only exist inside metadata.json, so this
        // opens one per gallery before the list can show anything. `reads` is therefore the number
        // that matters — the elapsed time on its own says nothing without knowing how many folders
        // it covered.
        long tLoad = SystemClock.elapsedRealtime();
        int reads = 0;

        // Collect (gallery, metadata.json mtime) entries: the mtime feeds the
        // DOWNLOAD_DATE_DESC ordering and isn't a field on GalleryInfo. Other modes ignore it.
        List<SmbSortMode.Entry> entries = new ArrayList<>();
        try {
            CIFSContext cifs = buildContext();
            SmbFile galleryRoot = new SmbFile(galleryRootUrl(), cifs);
            if (!galleryRoot.exists() || !galleryRoot.isDirectory()) {
                return new ArrayList<>();
            }
            SmbFile[] children = galleryRoot.listFiles();
            if (children == null) {
                return new ArrayList<>();
            }
            for (SmbFile child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                // Same gate as listGalleryRefs, so both orderings agree on which folders are
                // galleries at all. Also saves the exists() round trip below on every foreign
                // directory the share happens to carry.
                if (!SmbPaths.isGalleryFolderName(trimTrailingSlash(child.getName()))) {
                    continue;
                }
                SmbFile metadata = new SmbFile(child, METADATA_FILE);
                if (!metadata.exists()) {
                    continue;
                }
                String json;
                try (InputStream is = metadata.getInputStream()) {
                    json = readAll(is);
                }
                reads++;
                JSONObject object = JSONObject.parseObject(json);
                if (object == null) {
                    continue;
                }
                GalleryInfo info = GalleryInfo.galleryInfoFromJson(object);
                long mtime;
                try {
                    mtime = metadata.lastModified();
                } catch (Throwable ignored) {
                    mtime = 0L;
                }
                entries.add(new SmbSortMode.Entry(info, mtime));
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load SMB inventory", e);
            Log.w("SmbPerf", "inventory.load mode=" + mode + " reads=" + reads
                    + " FAILED after " + (SystemClock.elapsedRealtime() - tLoad) + "ms thr="
                    + Thread.currentThread().getName());
            // Return whatever was collected before the failure, in insertion order.
            return toGalleryList(entries);
        }

        // Sorting is in memory and should be nothing next to the reads above; timed separately so
        // that stays a fact rather than an assumption.
        long tSort = SystemClock.elapsedRealtime();
        Collections.sort(entries, mode.comparator());
        Log.i("SmbPerf", "inventory.load mode=" + mode + " n=" + entries.size()
                + " reads=" + reads + " " + (SystemClock.elapsedRealtime() - tLoad) + "ms"
                + " sort=" + (SystemClock.elapsedRealtime() - tSort) + "ms"
                + " thr=" + Thread.currentThread().getName());
        return toGalleryList(entries);
    }

    @NonNull
    private static List<GalleryInfo> toGalleryList(@NonNull List<SmbSortMode.Entry> entries) {
        List<GalleryInfo> out = new ArrayList<>(entries.size());
        for (SmbSortMode.Entry e : entries) {
            out.add(e.info);
        }
        return out;
    }

    /**
     * A gallery folder located on the share but not yet read. {@link #loadInventory} reads every
     * {@code metadata.json} up front before the list can show anything, which is O(folders) SMB
     * round-trips on the first paint. The Local Inventory instead lists these refs once (a single
     * share-root enumeration) and reads each folder's metadata lazily — only for the rows actually
     * scrolled into view (see {@link #readGalleryInfo}).
     *
     * <p>{@link #folderMtime} is the folder's own modification time, which the directory enumeration
     * already carries (no extra round-trip), so it can order the default "recently downloaded first"
     * view without reading a single metadata file. It tracks the last write into the gallery folder,
     * i.e. effectively when the download finished — equivalent to the old {@code metadata.json} mtime
     * for ordering purposes.
     */
    public static final class GalleryRef {
        @NonNull public final String folderName;
        public final long folderMtime;

        public GalleryRef(@NonNull String folderName, long folderMtime) {
            this.folderName = folderName;
            this.folderMtime = folderMtime;
        }
    }

    /**
     * Enumerates the gallery folders on the share in one listing, WITHOUT reading any
     * {@code metadata.json}. Cheap enough to drive the first paint of the Local Inventory; callers
     * read each folder's metadata on demand via {@link #readGalleryInfo}.
     */
    @NonNull
    public static List<GalleryRef> listGalleryRefs() {
        List<GalleryRef> refs = new ArrayList<>();
        if (!isConfigured()) {
            return refs;
        }
        // Timed here rather than only at the callers. SmbSavedGalleries already reports its own
        // `list=` figure, but that is one caller's view of this; the Local Inventory's first paint
        // goes through the same call and had no number at all.
        long t0 = SystemClock.elapsedRealtime();
        try {
            CIFSContext cifs = buildContext();
            SmbFile galleryRoot = new SmbFile(galleryRootUrl(), cifs);
            if (!galleryRoot.exists() || !galleryRoot.isDirectory()) {
                return refs;
            }
            SmbFile[] children = galleryRoot.listFiles();
            if (children == null) {
                return refs;
            }
            for (SmbFile child : children) {
                // type and timestamps are populated by the directory enumeration, so isDirectory()
                // and lastModified() here don't cost extra round-trips.
                if (!child.isDirectory()) {
                    continue;
                }
                String name = trimTrailingSlash(child.getName());
                // Not every directory here is a gallery — see SmbPaths.isGalleryFolderName. Foreign
                // ones used to be counted as galleries, which inflated the page count (a page could
                // come out empty) and cost a wasted round trip each when readGalleryInfo went
                // looking for their metadata.
                if (!SmbPaths.isGalleryFolderName(name)) {
                    continue;
                }
                // Sort key: folder CREATION time, i.e. when the download created the folder.
                // The previous key, lastModified(), gets bumped by reading - persisting the
                // reading progress renames .ehviewer inside the folder, which touches the
                // directory mtime - so any gallery you read jumped to the top of the
                // "recently downloaded" order on the next refresh. createTime comes from the
                // same directory enumeration, so it stays free of extra round-trips.
                long mtime;
                try {
                    mtime = child.createTime();
                } catch (Throwable ignored) {
                    mtime = 0L;
                }
                if (mtime == 0L) {
                    try {
                        mtime = child.lastModified();
                    } catch (Throwable ignored) {
                        mtime = 0L;
                    }
                }
                refs.add(new GalleryRef(name, mtime));
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to list SMB gallery folders", e);
        }
        Log.i("SmbPerf", "inventory.refs n=" + refs.size() + " "
                + (SystemClock.elapsedRealtime() - t0) + "ms thr="
                + Thread.currentThread().getName());
        return refs;
    }

    /**
     * Reads one gallery folder's {@code metadata.json} into a {@link GalleryInfo}. Returns
     * {@code null} when the folder has no parseable metadata. Safe to call off the main thread, one
     * folder at a time, as rows scroll into view.
     */
    @Nullable
    public static GalleryInfo readGalleryInfo(@NonNull GalleryRef ref) {
        if (!isConfigured()) {
            return null;
        }
        // One line per call, like materialize and preview: this runs once per row as rows scroll
        // into view, so it is the per-row cost, and an average would hide the one folder that takes
        // ten times the rest.
        long t0 = SystemClock.elapsedRealtime();
        try {
            CIFSContext cifs = buildContext();
            SmbFile galleryRoot = new SmbFile(galleryRootUrl(), cifs);
            SmbFile folder = new SmbFile(galleryRoot, ref.folderName + "/");
            SmbFile metadata = new SmbFile(folder, METADATA_FILE);
            if (!metadata.exists()) {
                Log.i("SmbPerf", "inventory.info " + ref.folderName + " missing "
                        + (SystemClock.elapsedRealtime() - t0) + "ms thr="
                        + Thread.currentThread().getName());
                return null;
            }
            String json;
            try (InputStream is = metadata.getInputStream()) {
                json = readAll(is);
            }
            JSONObject object = JSONObject.parseObject(json);
            if (object == null) {
                return null;
            }
            GalleryInfo info = GalleryInfo.galleryInfoFromJson(object);
            Log.i("SmbPerf", "inventory.info gid=" + info.gid + " bytes=" + json.length()
                    + " " + (SystemClock.elapsedRealtime() - t0) + "ms thr="
                    + Thread.currentThread().getName());
            return info;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to read SMB gallery metadata: " + ref.folderName, e);
            Log.w("SmbPerf", "inventory.info " + ref.folderName + " EXCEPTION after "
                    + (SystemClock.elapsedRealtime() - t0) + "ms: " + e);
            return null;
        }
    }

    /**
     * Reads a gallery's {@code metadata.json} given only enough of a {@link GalleryInfo} to name
     * its folder — the gid and title. Returns {@code null} if it is not there or not parseable.
     *
     * <p>Exists for the download list (#59), where a shared task carries the queue's fields and
     * nothing else. Everything a row wants beyond that — category, cover, rating — is already on
     * the share, written as a skeleton the moment a gallery is enqueued, so it is read from there
     * rather than copied into {@code state/} and kept in step.
     *
     * <p>Performs SMB I/O; call from a worker thread, and cache the result.
     */
    @Nullable
    public static GalleryInfo readGalleryMetadata(@NonNull GalleryInfo hint) {
        if (!isConfigured()) {
            return null;
        }
        try {
            SmbFile metadata = new SmbFile(resolveGalleryDir(hint), METADATA_FILE);
            if (!metadata.exists()) {
                return null;
            }
            String json;
            try (InputStream is = metadata.getInputStream()) {
                json = readAll(is);
            }
            JSONObject object = JSONObject.parseObject(json);
            return object == null ? null : GalleryInfo.galleryInfoFromJson(object);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read metadata for gid=" + hint.gid, e);
            return null;
        }
    }

    /** jcifs reports directory names with a trailing slash; the gallery folder name has none. */
    @NonNull
    private static String trimTrailingSlash(@NonNull String name) {
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }

    private static String readAll(InputStream is) throws IOException {
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

    /**
     * Copies one stream into another, at least one end of which is always on the share.
     *
     * <p>Deliberately not {@code IOUtils.copy}: its buffer is 4 KB, the slowest setting available
     * here, and it is a shared utility so widening it would change every unrelated local-file copy
     * in the app too.
     */
    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] chunk = new byte[SMB_IO_BUFFER];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            out.flush();
        } finally {
            IOUtils.closeQuietly(in);
            IOUtils.closeQuietly(out);
        }
    }
}
