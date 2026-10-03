package com.hippo.ehviewer.smb;

import androidx.annotation.Nullable;

import com.hippo.conaco.DataContainer;
import com.hippo.conaco.ProgressNotifier;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.streampipe.InputStreamPipe;

import java.io.InputStream;

/** Holds gid and title only: a GalleryInfo back-reference cycles when parcelled. */
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
        // The share is the authoritative copy: never write network bytes back.
        return false;
    }

    @Nullable
    @Override
    public InputStreamPipe get() {
        InputStreamPipe buffered = SmbPreviewCache.pipeFor(mGid, mIndex);
        if (buffered != null) {
            return buffered;
        }
        return NetworkStorage.active().files().openImageInputStreamPipe(NetworkStorage.lookupKey(mGid, mTitle), mIndex);
    }

    @Override
    public void remove() {
    }

    /** The share stays the only durable copy of a page. */
    @Override
    public boolean allowDiskCopy() {
        return false;
    }
}
