package com.hippo.ehviewer.smb;

import androidx.annotation.Nullable;

import com.hippo.conaco.DataContainer;
import com.hippo.conaco.ProgressNotifier;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.streampipe.InputStreamPipe;

import java.io.IOException;
import java.io.InputStream;

/** Holds gid and title only: a GalleryInfo back-reference cycles when parcelled. */
public class SmbCoverDataContainer implements DataContainer {

    private final long mGid;
    @Nullable private final String mTitle;

    public SmbCoverDataContainer(long gid, @Nullable String title) {
        mGid = gid;
        mTitle = title;
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
        InputStreamPipe buffered = SmbCoverPrefetch.pipeFor(mGid);
        if (buffered != null) {
            return buffered;
        }
        return NetworkStorage.active().files().openCoverInputStreamPipe(NetworkStorage.lookupKey(mGid, mTitle));
    }

    @Override
    public void remove() {
    }

    /** The share stays the only durable copy of a cover. */
    @Override
    public boolean allowDiskCopy() {
        return false;
    }
}
