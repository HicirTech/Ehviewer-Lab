package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.storage.DownloadState;

/**
 * An SMB download as a DownloadInfo, so the list treats it like any other row while actions
 * route by type to the SMB layer. Never in the database — built from state/, discarded on refresh.
 */
public final class SmbTaskInfo extends DownloadInfo {

    /** Which device is doing this, as a human would name it. */
    @NonNull
    public final String deviceName;

    /** That device's identity on the share; matches this one for our own tasks. */
    @NonNull
    public final String ownerClientId;

    /** False once the owner has stopped writing its heartbeat — the task is up for adoption. */
    public final boolean ownerAlive;

    /** True when this device owns it, and may therefore act on it freely. */
    public final boolean mine;

    /** True when it is somebody else's abandoned work, and so open to being adopted. */
    public final boolean takeOverable;

    /** Owner's last heartbeat (share clock, 0 unknown) — what a takeover is judged by. */
    public final long lastSeenMillis;

    private SmbTaskInfo(@NonNull DownloadState.OwnedTask owned, @NonNull String selfClientId,
                        int state,
                        @Nullable com.hippo.ehviewer.client.data.GalleryInfo metadata) {
        this.gid = owned.task.gid;
        this.token = owned.task.token;
        this.title = owned.task.title;
        this.pages = owned.task.total;
        this.finished = owned.task.finished;
        this.total = owned.task.total;
        this.downloaded = owned.task.finished;
        this.time = owned.task.claimedAt;
        this.state = state;
        this.deviceName = owned.deviceName;
        this.lastSeenMillis = owned.lastSeenMillis;
        // The queue file says what is being downloaded and by whom; everything else a row draws
        // comes from the gallery's own metadata on the share. Absent while a gallery is still
        // being enqueued, and the row simply renders without it.
        if (metadata != null) {
            this.category = metadata.category;
            this.thumb = metadata.thumb;
            this.rating = metadata.rating;
            this.posted = metadata.posted;
            this.simpleLanguage = metadata.simpleLanguage;
            if (this.title == null) {
                this.title = metadata.title;
            }
            if (this.pages <= 0) {
                this.pages = metadata.pages;
            }
        }
        this.ownerClientId = owned.clientId;
        this.ownerAlive = owned.ownerAlive;
        // Asked of the merged entry rather than worked out again here. Who may do what is one
        // rule, and it belongs with the data it is about; stating it twice is how the two come to
        // disagree.
        this.mine = owned.isActionableBy(selfClientId);
        this.takeOverable = owned.isTakeOverableBy(selfClientId);
    }

    /** Adapts one merged entry for the list; state resolved by the downloader. */
    @NonNull
    public static SmbTaskInfo of(@NonNull DownloadState.OwnedTask owned,
                                 @NonNull String selfClientId,
                                 @Nullable com.hippo.ehviewer.client.data.GalleryInfo metadata,
                                 int state) {
        return new SmbTaskInfo(owned, selfClientId, state, metadata);
    }

    /** Convenience for the adapter, which has a {@link DownloadInfo} and no idea what kind. */
    public static boolean isSmb(@Nullable DownloadInfo info) {
        return info instanceof SmbTaskInfo;
    }

    /** Only own items are actionable; an abandoned one must be adopted first. */
    public static boolean isActionable(@Nullable DownloadInfo info) {
        if (!(info instanceof SmbTaskInfo)) {
            return true;   // an ordinary download, handled the ordinary way
        }
        return ((SmbTaskInfo) info).mine;
    }

    /** Whether the item is somebody else's abandoned work, and so open to being adopted. */
    public static boolean canTakeOver(@Nullable DownloadInfo info) {
        return info instanceof SmbTaskInfo && ((SmbTaskInfo) info).takeOverable;
    }
}
