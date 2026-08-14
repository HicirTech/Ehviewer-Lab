/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.spider;

import android.graphics.BitmapFactory;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.EhCacheKeyFactory;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.gallery.GalleryProvider2;
import com.hippo.ehviewer.smb.SmbSpiderStorage;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.Utilities;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.unifile.UniFile;

import java.io.InputStream;

/**
 * Puts pages the device already holds onto the remote backend (#16, #88, #95).
 *
 * <p>Two hands can be holding a page before the share does: the image cache (a page just viewed)
 * and phone storage (a gallery downloaded before it was moved). Fetching such a page from
 * e-hentai again is the thing this class exists to avoid — whichever hand it is in, the copy is
 * lifted from there. {@code SpiderDen.contain} calls both in order, and "move a download to the
 * share" is nothing more than an ordinary SMB download whose every page happens to be found by
 * this bridge.
 *
 * <p>Fork-owned: this logic lived as static and instance blocks inside upstream's
 * {@code SpiderDen}, where every upstream merge had to route around it. The den keeps the mode
 * decisions and the call sites; the bridge keeps the copying. It talks to the backend through
 * {@link GallerySpiderStorage}, not to SMB directly — the one SMB mention is the factory that
 * resolves the backend, the same seam {@code SpiderDen.remoteStorage()} uses.
 */
public final class RemotePageBridge {

    private static final String TAG = "RemotePageBridge";

    private final GalleryInfo mGalleryInfo;
    private final long mGid;

    private final Object mPhoneCopyLock = new Object();
    private boolean mPhoneCopyResolved;
    @Nullable
    private UniFile mPhoneCopyDir;

    RemotePageBridge(@NonNull GalleryInfo galleryInfo, long gid) {
        mGalleryInfo = galleryInfo;
        mGid = gid;
    }

    /**
     * Puts the cached copy of one page onto the SMB share, replacing whatever is there (#16).
     *
     * <p>For the reader's "refresh this page": a page whose file on the share is corrupt reads back
     * corrupt no matter how often it is re-requested, because the re-download lands in the cache
     * and {@code SpiderDen.openOutputStreamPipe} deliberately refuses to write to the share while
     * reading — otherwise every page anyone looked at would start an SMB write. This is the narrow
     * exception: one page, asked for by hand, copied over once the good bytes are already in hand.
     *
     * <p>Copied rather than downloaded straight to the share, and copied only after the fetch has
     * succeeded, so the file on the share is only ever replaced by something that exists. Deleting
     * it first and re-fetching would be simpler and would leave the gallery worse than it started
     * whenever the network is down.
     *
     * <p>The write itself is atomic (temp name, then rename), so no reader ever sees a half-written
     * page either.
     *
     * <p>Performs SMB I/O; call from a worker thread. Static because one caller — the reader's
     * refresh in {@code EhGalleryProvider} — has a gallery in hand but no den.
     */
    public static boolean copyFromCacheToRemote(@NonNull GalleryInfo info, int index) {
        if (SpiderDen.sCache == null) {
            return false;
        }
        GallerySpiderStorage remote = SmbSpiderStorage.createIfTarget(info, info.gid);
        if (remote == null) {
            return false;
        }
        String key = EhCacheKeyFactory.getImageKey(info.gid, index);
        InputStreamPipe pipe = SpiderDen.sCache.getInputStreamPipe(key);
        if (pipe == null) {
            return false;
        }
        OutputStreamPipe osPipe = null;
        try {
            // The extension has to come from the bytes: the share names pages by it, and a
            // re-download can legitimately come back in a different format from the one there now.
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            pipe.obtain();
            BitmapFactory.decodeStream(pipe.open(), null, options);
            pipe.close();
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(options.outMimeType);
            if (extension == null) {
                return false;
            }
            extension = fixExtension('.' + extension);

            osPipe = remote.openImageOutputStreamPipe(index, extension);
            if (osPipe == null) {
                return false;
            }
            osPipe.obtain();
            pipe.obtain();
            IOUtils.copy(pipe.open(), osPipe.open());
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Could not put the cached page on the share gid=" + info.gid
                    + ", index=" + index, e);
            return false;
        } finally {
            if (osPipe != null) {
                osPipe.close();
                osPipe.release();
            }
            pipe.close();
            pipe.release();
        }
    }

    private static String fixExtension(String extension) {
        if (Utilities.contain(GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS, extension)) {
            return extension;
        }
        return GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS[0];
    }

    /**
     * This gallery's folder in phone storage, if it has one.
     *
     * <p>Resolved once per bridge and remembered, including the answer "there is none". The lookup
     * lists the download directory when the database has no name for the gallery, and asking that
     * once per page would put a storage-access-framework listing between every page of a download.
     *
     * <p>{@code getExistingGalleryDownloadDir} never creates anything: a gallery that was never
     * downloaded to the phone leaves no trace in the download database from being asked about.
     */
    @Nullable
    private UniFile phoneCopyDir() {
        synchronized (mPhoneCopyLock) {
            if (!mPhoneCopyResolved) {
                mPhoneCopyResolved = true;
                mPhoneCopyDir = SpiderDen.getExistingGalleryDownloadDir(mGalleryInfo);
            }
            return mPhoneCopyDir;
        }
    }

    /**
     * Puts a page the phone already holds onto the share, and says whether it managed to.
     *
     * <p>The other half of "move a download to the share": with this, moving is an ordinary SMB
     * download whose pages happen to be found locally instead of fetched. It is not limited to
     * moves, because there is no reason to re-download a page from e-hentai when the same page is
     * sitting in phone storage.
     */
    public boolean copyFromPhone(int index, @NonNull GallerySpiderStorage remote) {
        UniFile dir = phoneCopyDir();
        if (dir == null) {
            return false;
        }
        for (String extension : GalleryProvider2.SUPPORT_IMAGE_EXTENSIONS) {
            UniFile file = dir.findFile(SpiderDen.generateImageFilename(index, extension));
            if (file == null) {
                continue;
            }
            OutputStreamPipe osPipe = null;
            InputStream is = null;
            try {
                osPipe = remote.openImageOutputStreamPipe(index, extension);
                if (osPipe == null) {
                    return false;
                }
                osPipe.obtain();
                is = file.openInputStream();
                IOUtils.copy(is, osPipe.open());
                return true;
            } catch (Throwable e) {
                Log.w(TAG, "Could not put the phone's copy of page " + index
                        + " on the share, gid=" + mGid, e);
                return false;
            } finally {
                IOUtils.closeQuietly(is);
                if (osPipe != null) {
                    osPipe.close();
                    osPipe.release();
                }
            }
        }
        return false;
    }
}
