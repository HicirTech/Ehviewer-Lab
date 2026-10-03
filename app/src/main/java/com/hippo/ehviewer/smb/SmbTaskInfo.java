package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.storage.DownloadState;

/** Never in the database: built from state/ and discarded on refresh. */
public final class SmbTaskInfo extends DownloadInfo {

    @NonNull
    public final String deviceName;

    @NonNull
    public final String ownerClientId;

    public final boolean ownerAlive;

    public final boolean mine;

    public final boolean takeOverable;

    /** By the share's clock; 0 when unknown. */
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
        this.mine = owned.isActionableBy(selfClientId);
        this.takeOverable = owned.isTakeOverableBy(selfClientId);
    }

    @NonNull
    public static SmbTaskInfo of(@NonNull DownloadState.OwnedTask owned,
                                 @NonNull String selfClientId,
                                 @Nullable com.hippo.ehviewer.client.data.GalleryInfo metadata,
                                 int state) {
        return new SmbTaskInfo(owned, selfClientId, state, metadata);
    }

    public static boolean isSmb(@Nullable DownloadInfo info) {
        return info instanceof SmbTaskInfo;
    }

    public static boolean isActionable(@Nullable DownloadInfo info) {
        if (!(info instanceof SmbTaskInfo)) {
            return true;
        }
        return ((SmbTaskInfo) info).mine;
    }

    public static boolean canTakeOver(@Nullable DownloadInfo info) {
        return info instanceof SmbTaskInfo && ((SmbTaskInfo) info).takeOverable;
    }
}
