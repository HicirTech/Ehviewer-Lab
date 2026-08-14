package com.hippo.ehviewer.smb;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.util.IoThreadPoolExecutor;

/**
 * Coordinates "Save to SMB" enqueues from both the gallery reader (auto) and the gallery
 * detail screen (manual).
 * <p>
 * There is no local record of what is already being saved. There used to be an in-memory set of
 * gids, and keeping it alongside the claims on the share would mean two answers to one question,
 * free to disagree — a gid stuck in the set no longer had any way out, and the enqueue would
 * silently do nothing until the app restarted. The share is now the only place that says who is
 * downloading what: another device's claim is checked here, and this device's own queue turns a
 * repeat enqueue into a no-op in {@code SmbDirectDownloader.start}.
 * <p>
 * The auto path runs only when both {@link Settings#getSmbSaveEnabled()} and
 * {@link Settings#getSmbAutoDownloadEnabled()} are true. The manual path only requires
 * the master save switch (so the user can opt-in per gallery from the Download button).
 * <p>
 * Common behaviour:
 * <ol>
 *   <li>Skip galleries whose on-share copy already has all images present
 *       ({@link SmbStorage#isGalleryComplete}).</li>
 *   <li>Skip galleries another live device has already claimed
 *       ({@code SmbDirectDownloader.isClaimedElsewhere}).</li>
 *   <li>Write a skeleton {@code metadata.json} immediately so Local Inventory lists the
 *       gallery before/even without a finished download.</li>
 *   <li>Hand the gallery off to {@link SmbDirectDownloader} for the actual download.</li>
 * </ol>
 */
public final class SmbAutoDownloadManager {

    private static final String TAG = "SmbAutoDownloadMgr";
    private static final SmbAutoDownloadManager INSTANCE = new SmbAutoDownloadManager();

    private SmbAutoDownloadManager() {}

    public static SmbAutoDownloadManager getInstance() {
        return INSTANCE;
    }

    /** Called from the reader on first page open. Auto-download must be explicitly enabled. */
    public void enqueueFromFirstPage(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        if (!Settings.getSmbSaveEnabled() || !Settings.getSmbAutoDownloadEnabled()
                || !SmbConnection.isConfigured()) {
            return;
        }
        enqueueInternal(context, galleryInfo);
    }

    /** Called from the detail screen "Save to SMB" choice. Bypasses the auto-download toggle. */
    public void enqueueManual(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        if (!Settings.getSmbSaveEnabled() || !SmbConnection.isConfigured()) {
            Toast.makeText(context.getApplicationContext(),
                    R.string.smb_save_not_configured, Toast.LENGTH_SHORT).show();
            return;
        }
        enqueueInternal(context, galleryInfo);
    }

    private void enqueueInternal(@NonNull Context context, @NonNull GalleryInfo galleryInfo) {
        final Context appContext = context.getApplicationContext();

        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            try {
                if (SmbGalleryLifecycle.isGalleryComplete(galleryInfo)) {
                    toast(appContext, appContext.getString(R.string.smb_save_already_complete));
                    return;
                }
                // Another device may already be on it. Checked here rather than in the downloader
                // because this is the one place a gallery enters the queue from outside, and
                // because there is already an SMB round trip on this thread to share.
                if (SmbDirectDownloader.getInstance().isClaimedElsewhere(galleryInfo.gid)) {
                    toast(appContext, appContext.getString(R.string.smb_save_claimed_elsewhere));
                    return;
                }
                try {
                    SmbMetadata.writeMetadataSkeleton(galleryInfo);
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to write skeleton metadata gid=" + galleryInfo.gid, e);
                }
                // Announced only now, once the gates are behind us. Saying it up front read as an
                // answer -- and then the real one arrived a moment later contradicting it, so a
                // gallery another device already had looked like it had started and then changed
                // its mind. The cost is that nothing is said for a round trip or two; the dialog
                // closing already acknowledges the tap.
                toast(appContext, appContext.getString(R.string.smb_save_started,
                        galleryInfo.title != null ? galleryInfo.title
                                : ("gid " + galleryInfo.gid)));
                SimpleHandler.getInstance().post(() ->
                        SmbDirectDownloader.getInstance().start(appContext, galleryInfo));
            } catch (Throwable e) {
                Log.e(TAG, "enqueueInternal failed gid=" + galleryInfo.gid, e);
                // Previously this said nothing at all, so an unreachable share left the user with
                // a "save started" and then silence forever.
                toast(appContext, appContext.getString(R.string.smb_save_failed));
            }
        });
    }

    /**
     * Both callers reach here off the main thread -- the auto path from
     * {@code GalleryView.render()}, the manual one from an IO task -- and
     * {@code Toast.makeText().show()} throws anywhere without a prepared Looper.
     */
    private static void toast(@NonNull Context appContext, @NonNull String text) {
        SimpleHandler.getInstance().post(() ->
                Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show());
    }
}
