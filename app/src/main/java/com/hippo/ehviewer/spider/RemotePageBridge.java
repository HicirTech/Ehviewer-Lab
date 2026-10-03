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
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.IOUtils;
import com.hippo.lib.yorozuya.Utilities;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.unifile.UniFile;

import java.io.InputStream;

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

    /** Worker thread; call only after the page was refetched into the cache. */
    public static boolean copyFromCacheToRemote(@NonNull GalleryInfo info, int index) {
        if (SpiderDen.sCache == null) {
            return false;
        }
        GallerySpiderStorage remote = NetworkStorage.active().spiderStorage(info, info.gid);
        if (remote == null) {
            return false;
        }
        String key = EhCacheKeyFactory.getImageKey(info.gid, index);
        InputStreamPipe pipe = SpiderDen.sCache.getInputStreamPipe(key);
        if (pipe == null) {
            return false;
        }
        OutputStreamPipe osPipe = null;
        boolean copied = false;
        boolean opened = false;
        try {
            // Extension from the bytes: a re-download may come back in a different format.
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
            java.io.OutputStream os = osPipe.open();
            opened = true;
            IOUtils.copy(pipe.open(), os);
            copied = true;
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Could not put the cached page on the share gid=" + info.gid
                    + ", index=" + index, e);
            return false;
        } finally {
            if (osPipe != null) {
                osPipe.close();
                osPipe.release();
                if (opened && !copied) {
                    // The pipe publishes even a truncated page on close.
                    remote.removeImage(index);
                }
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

    /** Memoized: a SAF listing per page would crawl. */
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
            boolean copied = false;
            boolean opened = false;
            try {
                osPipe = remote.openImageOutputStreamPipe(index, extension);
                if (osPipe == null) {
                    return false;
                }
                osPipe.obtain();
                is = file.openInputStream();
                java.io.OutputStream os = osPipe.open();
                opened = true;
                IOUtils.copy(is, os);
                copied = true;
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
                    if (opened && !copied) {
                        remote.removeImage(index);
                    }
                }
            }
        }
        return false;
    }
}
