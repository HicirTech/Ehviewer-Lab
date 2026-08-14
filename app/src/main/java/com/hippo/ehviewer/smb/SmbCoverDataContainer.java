package com.hippo.ehviewer.smb;

import androidx.annotation.Nullable;

import com.hippo.conaco.DataContainer;
import com.hippo.conaco.ProgressNotifier;
import com.hippo.streampipe.InputStreamPipe;

import java.io.IOException;
import java.io.InputStream;

/**
 * Conaco container reading the cover off the share instead of the network. Holds gid+title only
 * (parcel cycle).
 */
public class SmbCoverDataContainer implements DataContainer {

    private final long mGid;
    @Nullable private final String mTitle;

    public SmbCoverDataContainer(long gid, @Nullable String title) {
        mGid = gid;
        mTitle = title;
    }

    @Override
    public boolean isEnabled() {
        return SmbConnection.isConfigured();
    }

    @Override
    public void onUrlMoved(String requestUrl, String responseUrl) {
    }

    @Override
    public boolean save(InputStream is, long length, @Nullable String mediaType,
                        @Nullable ProgressNotifier notify) {
        // SMB is the authoritative copy; if Conaco still falls back to network here we
        // intentionally don't re-write it back.
        return false;
    }

    @Nullable
    @Override
    public InputStreamPipe get() {
        // The prefetch buffer first: bytes SmbCoverPrefetch pulled off the share in parallel,
        // held in memory only. Falling through is the ordinary path, not a failure — a cover the
        // prefetch has not reached, lost to LRU, or never had is read from the share exactly as
        // it always was.
        InputStreamPipe buffered = SmbCoverPrefetch.pipeFor(mGid);
        if (buffered != null) {
            return buffered;
        }
        return SmbGalleryFiles.openSmbCoverInputStreamPipe(SmbGalleryDirectory.lookupKey(mGid, mTitle));
    }

    @Override
    public void remove() {
    }
}
