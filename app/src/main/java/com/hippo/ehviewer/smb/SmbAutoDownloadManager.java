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

/**
 * "Save to SMB" enqueues, quiet (Power Download's automatic rules, #159) and manual (detail,
 * reader menu, volume key). No local already-saving record — the share's claims are the only
 * answer (a shadow set once wedged silently). Skips galleries this device is already saving,
 * complete ones and claimed ones, writes the metadata skeleton, hands off to SmbDirectDownloader.
 */
public final class SmbAutoDownloadManager {

    private static final String TAG = "SmbAutoDownloadMgr";
    private static final SmbAutoDownloadManager INSTANCE = new SmbAutoDownloadManager();

    private SmbAutoDownloadManager() {}

    public static SmbAutoDownloadManager getInstance() {
        return INSTANCE;
    }

    /**
     * Power Download's automatic rules (#159): says nothing unless a save actually starts or
     * fails, since they fire while the user reads.
     */
    public void enqueueQuietly(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        if (!SmbDownloadBoard.smbAvailable()) {
            return;
        }
        enqueueInternal(context, galleryInfo, true);
    }

    /** A deliberate ask: the detail screen's "Save to SMB", a reader menu entry or a volume key. */
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
        // The downloader's own queue, not a record of what was asked: asking again while it is
        // saving costs no round trip and must not announce a second start (#159).
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
                // Another device may already be on it. Checked here rather than in the downloader
                // because this is the one place a gallery enters the queue from outside, and
                // because there is already an SMB round trip on this thread to share.
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
                // Announced only now, once the gates are behind us. Saying it up front read as an
                // answer -- and then the real one arrived a moment later contradicting it, so a
                // gallery another device already had looked like it had started and then changed
                // its mind. The cost is that nothing is said for a round trip or two; the dialog
                // closing already acknowledges the tap.
                toast(appContext, appContext.getString(R.string.smb_save_started,
                        NetworkStorage.active().displayName(),
                        galleryInfo.title != null ? galleryInfo.title
                                : ("gid " + galleryInfo.gid)));
                SimpleHandler.getInstance().post(() ->
                        SmbDirectDownloader.getInstance().start(appContext, galleryInfo));
            } catch (Throwable e) {
                Log.e(TAG, "enqueueInternal failed gid=" + galleryInfo.gid, e);
                // Previously this said nothing at all, so an unreachable share left the user with
                // a "save started" and then silence forever.
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
