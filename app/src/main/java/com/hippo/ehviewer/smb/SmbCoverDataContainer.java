package com.hippo.ehviewer.smb;

import androidx.annotation.Nullable;

import com.hippo.conaco.DataContainer;
import com.hippo.conaco.ProgressNotifier;
import com.hippo.streampipe.InputStreamPipe;

import java.io.IOException;
import java.io.InputStream;

/**
 * Conaco {@link DataContainer} that reads the gallery cover directly from the SMB share
 * instead of fetching {@link com.hippo.ehviewer.client.data.GalleryInfo#thumb} over the
 * network. Used by Local Inventory list cells so an offline SMB-only browse never hits
 * e-hentai for thumbnails that are already saved next to the gallery.
 *
 * <p>The actual on-share file is named {@code cover.<ext>} and was written at SMB-save
 * time by {@code SmbStorage.downloadAndWriteCover}. We probe every supported image
 * extension via {@link SmbStorage#openSmbCoverInputStreamPipe} since the upstream
 * Content-Type may have produced any of jpg/png/gif/webp.
 *
 * <p>Holds only primitives ({@code gid} + {@code title}) so the container can sit on a
 * parcelled GalleryInfo without creating a back-reference cycle.
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
        return SmbStorage.isConfigured();
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
        // The staged copy first. SmbCoverCache pulls a page's covers in parallel while the rows
        // are being built, so by the time Conaco's serial disk thread reaches this one the file
        // is usually already local and no SMB I/O happens on that thread at all.
        //
        // Falling through is not a failure: a cover the prefetch has not reached yet, or could not
        // fetch, is read from the share exactly as it was before this cache existed.
        final java.io.File staged = SmbCoverCache.staged(mGid);
        if (staged != null) {
            return new InputStreamPipe() {
                private java.io.FileInputStream fis;

                @Override public void obtain() {}
                @Override public void release() {}

                @Override
                public InputStream open() throws IOException {
                    if (fis != null) {
                        throw new IllegalStateException("Please close it first");
                    }
                    fis = new java.io.FileInputStream(staged);
                    return fis;
                }

                @Override
                public void close() {
                    com.hippo.lib.yorozuya.IOUtils.closeQuietly(fis);
                    fis = null;
                }
            };
        }
        return SmbStorage.openSmbCoverInputStreamPipe(SmbStorage.lookupKey(mGid, mTitle));
    }

    @Override
    public void remove() {
    }
}
