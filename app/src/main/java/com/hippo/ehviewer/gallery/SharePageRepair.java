/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.gallery;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.spider.RemotePageBridge;
import com.hippo.ehviewer.storage.GalleryTargets;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Pages the user refreshed in the reader: the only pages a reader writes back to the share. */
final class SharePageRepair {

    @NonNull
    private final GalleryInfo mGalleryInfo;
    private final Set<Integer> mRefreshed = Collections.synchronizedSet(new HashSet<>());

    SharePageRepair(@NonNull GalleryInfo galleryInfo) {
        mGalleryInfo = galleryInfo;
    }

    void onForceRequest(int index) {
        if (GalleryTargets.isMarked(mGalleryInfo.gid)) {
            mRefreshed.add(index);
        }
    }

    void onPageSuccess(@NonNull Context context, int index) {
        if (!mRefreshed.remove(index)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            if (RemotePageBridge.copyFromCacheToRemote(mGalleryInfo, index)) {
                return;
            }
            SimpleHandler.getInstance().post(() -> Toast.makeText(
                    appContext, appContext.getString(R.string.smb_page_repair_failed,
                            NetworkStorage.active().displayName()), Toast.LENGTH_SHORT).show());
        });
    }
}
