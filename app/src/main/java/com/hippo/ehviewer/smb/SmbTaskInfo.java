package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.dao.DownloadInfo;

/**
 * An SMB download shown in the ordinary download list (#59).
 *
 * <p>The list, its adapter, its sorting, filtering and search all speak {@link DownloadInfo}, so
 * these arrive as one — which is what makes an SMB save look like any other download instead of
 * living in a screen of its own.
 *
 * <p>Being a distinct type is the point, though. Every action on the list has to go somewhere
 * different for these: stopping one means telling the SMB downloader, not
 * {@code DownloadManager}, and deleting one means the share rather than a row in the database.
 * Branching on the item's own type means an action is routed correctly wherever the item turns up,
 * which a flag on the surrounding screen would not guarantee.
 *
 * <p>These are never written to the database. They are built from what the devices published under
 * {@code state/} and thrown away on the next refresh.
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

    /**
     * When the owning device last wrote its file, by the share's clock. Zero if unknown.
     *
     * <p>Shown on the row rather than kept for the liveness check alone: "last seen four minutes
     * ago" is what actually tells someone whether a download has stopped or is merely between
     * heartbeats, and it is the thing to judge a takeover by.
     */
    public final long lastSeenMillis;

    private SmbTaskInfo(@NonNull SmbDownloadState.OwnedTask owned, boolean mine) {
        this.gid = owned.task.gid;
        this.token = owned.task.token;
        this.title = owned.task.title;
        this.pages = owned.task.total;
        this.finished = owned.task.finished;
        this.total = owned.task.total;
        this.downloaded = owned.task.finished;
        this.time = owned.task.claimedAt;
        this.state = owned.ownerAlive ? stateOf(owned.task.state) : STATE_FAILED;
        this.deviceName = owned.deviceName;
        this.lastSeenMillis = owned.lastSeenMillis;
        this.ownerClientId = owned.clientId;
        this.ownerAlive = owned.ownerAlive;
        this.mine = mine;
    }

    /**
     * Adapts one merged entry for the list.
     *
     * @param selfClientId this device's identity, so it can tell its own work from everyone else's
     */
    @NonNull
    public static SmbTaskInfo of(@NonNull SmbDownloadState.OwnedTask owned,
                                 @NonNull String selfClientId) {
        return new SmbTaskInfo(owned, selfClientId.equals(owned.clientId));
    }

    /**
     * Maps to the states the list already knows how to draw.
     *
     * <p>Only reached for a task whose owner is still beating. One that has gone quiet is shown as
     * failed regardless of what it last said it was doing — nothing is happening to it, and
     * leaving it drawn as running would have it look busy forever.
     */
    private static int stateOf(@NonNull SmbDownloadState.TaskState state) {
        switch (state) {
            case ACTIVE:
                return STATE_DOWNLOAD;
            case PAUSED:
                return STATE_NONE;
            case QUEUED:
            default:
                return STATE_WAIT;
        }
    }

    /** Convenience for the adapter, which has a {@link DownloadInfo} and no idea what kind. */
    public static boolean isSmb(@Nullable DownloadInfo info) {
        return info instanceof SmbTaskInfo;
    }

    /**
     * Whether this device may pause, resume or delete the item.
     *
     * <p>Another device's download is not ours to stop — it would carry on regardless, since the
     * decision lives in the process doing the work — and not ours to delete, since taking it off
     * the list means editing a file only its owner may write. An abandoned one is no exception:
     * it must be taken over first, and is then simply this device's own.
     */
    public static boolean isActionable(@Nullable DownloadInfo info) {
        if (!(info instanceof SmbTaskInfo)) {
            return true;   // an ordinary download, handled the ordinary way
        }
        return ((SmbTaskInfo) info).mine;
    }

    /** Whether the item is somebody else's abandoned work, and so open to being adopted. */
    public static boolean canTakeOver(@Nullable DownloadInfo info) {
        if (!(info instanceof SmbTaskInfo)) {
            return false;
        }
        SmbTaskInfo smb = (SmbTaskInfo) info;
        return !smb.mine && !smb.ownerAlive;
    }
}
