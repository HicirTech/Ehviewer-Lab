package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.util.IoThreadPoolExecutor;

public final class SmbAutoDownloadManager {

    private static final String TAG = "SmbAutoDownloadMgr";
    private static final SmbAutoDownloadManager INSTANCE = new SmbAutoDownloadManager();

    private SmbAutoDownloadManager() {}

    public static SmbAutoDownloadManager getInstance() {
        return INSTANCE;
    }

    /** For automatic rules firing mid-read: toasts only a start or a failure. */
    public void enqueueQuietly(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        if (!SmbDownloadBoard.smbAvailable()) {
            return;
        }
        enqueueInternal(context, galleryInfo, true);
    }

    public void enqueueManual(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        if (!SmbDownloadBoard.smbAvailable()) {
            Toast.makeText(context.getApplicationContext(),
                    context.getString(R.string.smb_save_not_configured, NetworkStorage.active().displayName()),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        enqueueInternal(context, galleryInfo, false);
    }

    private void enqueueInternal(@NonNull Context context, @NonNull GalleryInfo galleryInfo,
                                 boolean quiet) {
        // Before the skeleton write below: that alone would put a folder for it on the share.
        if (SmbDirectDownloader.isLocalImport(galleryInfo)) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        // The downloader's live queue: a separate record of asks can drift and wedge.
        if (SmbDirectDownloader.getInstance().isQueuedOrRunning(galleryInfo.gid)) {
            if (!quiet) {
                toast(appContext, appContext.getString(R.string.smb_save_already_running, NetworkStorage.active().displayName()));
            }
            return;
        }
        com.hippo.ehviewer.ui.NotificationPermission.onDownloadStart(context);

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                if (NetworkStorage.active().lifecycle().isGalleryComplete(galleryInfo)) {
                    if (!quiet) {
                        toast(appContext, appContext.getString(R.string.smb_save_already_complete, NetworkStorage.active().displayName()));
                    }
                    return;
                }
                if (SmbDownloadBoard.getInstance().isClaimedElsewhere(galleryInfo.gid)) {
                    if (!quiet) {
                        toast(appContext, appContext.getString(R.string.smb_save_claimed_elsewhere, NetworkStorage.active().displayName()));
                    }
                    return;
                }
                try {
                    NetworkStorage.active().metadata().writeMetadataSkeleton(galleryInfo);
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to write skeleton metadata gid=" + galleryInfo.gid, e);
                }
                // Only past the gates: an earlier "started" could be contradicted a moment later.
                toast(appContext, appContext.getString(R.string.smb_save_started,
                        NetworkStorage.active().displayName(),
                        galleryInfo.title != null ? galleryInfo.title
                                : ("gid " + galleryInfo.gid)));
                SimpleHandler.getInstance().post(() ->
                        SmbDirectDownloader.getInstance().start(appContext, galleryInfo));
            } catch (Throwable e) {
                Log.e(TAG, "enqueueInternal failed gid=" + galleryInfo.gid, e);
                toast(appContext, appContext.getString(R.string.smb_save_failed, NetworkStorage.active().displayName()));
            }
        });
    }

    /** Callers are off the main thread; Toast needs a Looper. */
    private static void toast(@NonNull Context appContext, @NonNull String text) {
        SimpleHandler.getInstance().post(() ->
                Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show());
    }
}
