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
 * The gallery as a whole: completeness, delete, rename, download finalize. Per-gallery where
 * {@link SmbGalleryFiles} is per-file; only the download path needs this class.
 */
public final class SmbGalleryLifecycle {

    private static final String TAG = "SmbStorage";

    private SmbGalleryLifecycle() {}

    /** Deletes the gallery folder recursively. True when deleted or never there. */
    public static boolean deleteGalleryFolder(@NonNull GalleryInfo info) {
        try {
            SmbFile galleryDir = SmbGalleryDirectory.resolveGalleryDir(info);
            if (!galleryDir.exists()) {
                return true;
            }
            deleteSmbDirRecursive(galleryDir);
            return !galleryDir.exists();
        } catch (Throwable e) {
            Log.w(TAG, "Failed to delete SMB gallery folder gid=" + info.gid, e);
            return false;
        } finally {
            SmbGalleryDirectory.invalidateListing(info.gid);
        }
    }

    /** Depth-first (jcifs refuses non-empty dirs); child failures are logged and skipped. */
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

    /** Complete = metadata declares N pages and N image files are there. Worker thread only. */
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
            // Logged loudly: a silent false here means re-downloading a complete gallery.
            Log.w("SmbPerf", "isGalleryComplete gid=" + info.gid + " EXCEPTION after "
                    + (SystemClock.elapsedRealtime() - tPerf) + "ms: " + e);
            return false;
        }
    }

    /** One list(), then in-memory counting — N×extensions round-trips once OOMed here. */
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

    // Retrying 0xc0000022 (another device's open handle); measured through in ~2s, 8s is slack.
    private static final long RENAME_DEADLINE_MS = 8_000L;
    private static final long RENAME_BACKOFF_START_MS = 100L;
    private static final long RENAME_BACKOFF_MAX_MS = 800L;

    /**
     * Renames the folder to match a new title (#86); must happen before the record is rewritten.
     * Refuses an occupied target. Worker thread only.
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
                    // One-arg renameTo on purpose: refuses rather than replaces an occupied target.
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
            // Never mutate info.pages: the instance is observed concurrently from the main thread.
            int resolvedPages = info.pages;
            if (resolvedPages <= 0) {
                int spiderPages = readPagesFromSpiderInfo(info);
                if (spiderPages > 0) {
                    resolvedPages = spiderPages;
                }
            }
            SmbMetadata.writeMetadataWithDetail(context, galleryDir, info, resolvedPages);
            downloadAndWriteCover(context, galleryDir, info);
            // The one moment this folder is certainly ours: sweep old temporaries (#75).
            SmbTempFiles.sweep(galleryDir, System.currentTimeMillis());
        } catch (Throwable e) {
            Log.e(TAG, "Failed to finalize SMB gallery gid=" + info.gid, e);
        } finally {
            // Every page just changed; a reader must not see the pre-download listing.
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

    /** Not IOUtils.copy: its 4KB buffer is the slowest SMB setting available. */
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
