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
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.SpiderDen;
import com.hippo.lib.yorozuya.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import jcifs.smb.SmbFile;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * What happens to a gallery on the share as a whole (#97): is it complete, deleting it,
 * renaming it, and finalizing it when its download ends.
 *
 * <p>The line against {@link SmbGalleryFiles} is per-gallery versus per-file: this class treats
 * the gallery folder as one thing with a state, and only reaches inside it for evidence (the
 * page count, the saved-image count) or for the finalize ritual — metadata, cover, temp-file
 * sweep. Everything here answers the download path; the reading path never needs it.
 *
 * <p>Split out of the old 1494-line {@code SmbStorage}; method bodies are verbatim from there.
 */
public final class SmbGalleryLifecycle {

    private static final String TAG = "SmbStorage";

    private SmbGalleryLifecycle() {}

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
            SmbFile galleryDir = SmbGalleryDirectory.resolveGalleryDir(info);
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
            SmbGalleryDirectory.invalidateListing(info.gid);
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
            SmbFile galleryDir = SmbGalleryDirectory.resolveGalleryDir(info);
            SmbFile metadata = new SmbFile(galleryDir, SmbMetadata.METADATA_FILE);
            if (!metadata.exists()) {
                return false;
            }
            int declaredPages = info.pages;
            try (InputStream is = metadata.getInputStream()) {
                String json = SmbGalleryFiles.readAll(is);
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
            jcifs.CIFSContext cifs = SmbConnection.buildContext();
            SmbFile galleryRoot = new SmbFile(SmbConnection.galleryRootUrl(), cifs);
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
                    SmbGalleryDirectory.invalidateListing(info.gid);
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
            SmbFile galleryDir = SmbGalleryDirectory.getGalleryDir(info);
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
            SmbGalleryDirectory.invalidateListing(info.gid);
        }
    }

    private static int readPagesFromSpiderInfo(@NonNull GalleryInfo info) {
        InputStream is = SmbGalleryFiles.openSpiderInfoInputStream(info);
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
                out = SmbGalleryFiles.openAtomicOutputStream(galleryDir, "cover" + extension, info.gid);
            } catch (IOException e) {
                IOUtils.closeQuietly(in);
                throw e;
            }
            copyStream(in, out);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to download cover", e);
        }
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
            byte[] chunk = new byte[SmbGalleryFiles.SMB_IO_BUFFER];
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
