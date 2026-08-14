package com.hippo.ehviewer.smb;

import androidx.annotation.Nullable;

import com.hippo.conaco.DataContainer;
import com.hippo.conaco.ProgressNotifier;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.streampipe.InputStreamPipe;

import java.io.InputStream;

/**
 * Conaco container for one on-share page: prefers the in-memory preview buffer, falls back to
 * the share. Holds gid+title only (parcel cycle).
 */
public class SmbImageDataContainer implements DataContainer {

    private final long mGid;
    private final String mTitle;
    private final int mIndex;

    public SmbImageDataContainer(long gid, @Nullable String title, int index) {
        mGid = gid;
        mTitle = title;
        mIndex = index;
    }

    @Override
    public boolean isEnabled() {
        return NetworkStorage.active().isConfigured();
    }

    @Override
    public void onUrlMoved(String requestUrl, String responseUrl) {
    }

    @Override
    public boolean save(InputStream is, long length, @Nullable String mediaType,
                        @Nullable ProgressNotifier notify) {
        // SMB is the authoritative copy; no need to cache the bytes back.
        return false;
    }

    @Nullable
    @Override
    public InputStreamPipe get() {
        InputStreamPipe buffered = SmbPreviewCache.pipeFor(mGid, mIndex);
        if (buffered != null) {
            return buffered;
        }
        // Fallback: prefetch hasn't reached this page yet, fetch from SMB on this thread.
        return NetworkStorage.active().files().openImageInputStreamPipe(NetworkStorage.lookupKey(mGid, mTitle), mIndex);
    }

    @Override
    public void remove() {
    }
}
