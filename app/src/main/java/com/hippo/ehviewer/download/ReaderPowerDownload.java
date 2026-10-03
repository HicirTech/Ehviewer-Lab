/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.download;

import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;

import java.util.Arrays;

/** Power Download in the reader: the page rules, the volume keys and the page menu entry. Main thread only. */
public final class ReaderPowerDownload {

    /** The gallery being read when it is an online one, else null. */
    public interface OnlineGallery {
        @Nullable
        GalleryInfo get();
    }

    private final OnlineGallery mGallery;
    private final PowerDownloadSession mSession = new PowerDownloadSession();

    public ReaderPowerDownload(@NonNull OnlineGallery gallery) {
        mGallery = gallery;
    }

    public void restoreFrom(@NonNull Bundle state) {
        mSession.restoreFrom(state);
    }

    public void saveTo(@NonNull Bundle state) {
        mSession.saveTo(state);
    }

    /** Call when the page index or the page count changes. */
    public void onPageShown(@NonNull Context context, int index, int size) {
        GalleryInfo info = downloadable();
        if (info == null) {
            return;
        }
        for (PowerDownloadTarget target : mSession.onPageShown(index, size)) {
            PowerDownloader.download(context, info, target, true);
        }
    }

    /** True when Power Download owns the key; its first press downloads. */
    public boolean onKeyDown(@NonNull Context context, int keyCode, @NonNull KeyEvent event) {
        PowerDownloadTarget target = volumeKeyTarget(keyCode);
        if (target == PowerDownloadTarget.NONE) {
            return false;
        }
        GalleryInfo info = downloadable();
        if (event.getRepeatCount() == 0 && info != null) {
            PowerDownloader.download(context, info, target, false);
        }
        return true;
    }

    public boolean ownsKey(int keyCode) {
        return volumeKeyTarget(keyCode) != PowerDownloadTarget.NONE;
    }

    /** Appended last, so the entries before it keep their positions. */
    @NonNull
    public CharSequence[] withMenuItem(@NonNull Context context, @NonNull CharSequence[] items) {
        if (!PowerDownloadSettings.isMenuEnabled() || downloadable() == null) {
            return items;
        }
        CharSequence[] withItem = Arrays.copyOf(items, items.length + 1);
        withItem[items.length] = PowerDownloadSettings.getMenuTarget().label(context);
        return withItem;
    }

    public void downloadFromMenu(@NonNull Context context) {
        GalleryInfo info = downloadable();
        if (info != null) {
            PowerDownloader.download(context, info, PowerDownloadSettings.getMenuTarget(), false);
        }
    }

    @Nullable
    private GalleryInfo downloadable() {
        GalleryInfo info = mGallery.get();
        return PowerDownloader.canDownload(info) ? info : null;
    }

    @NonNull
    private PowerDownloadTarget volumeKeyTarget(int keyCode) {
        if (Settings.getVolumePage() || !PowerDownloadSettings.isVolumeEnabled()
                || downloadable() == null) {
            return PowerDownloadTarget.NONE;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            return PowerDownloadSettings.getVolumeUpTarget();
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return PowerDownloadSettings.getVolumeDownTarget();
        }
        return PowerDownloadTarget.NONE;
    }
}
