package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import jcifs.smb.SmbFile;

/**
 * The temporary files an atomic write leaves behind when it does not finish, and who clears them
 * (#75).
 *
 * <p>Every write to the share goes to a temporary name and is renamed over its target, so that a
 * device reading at the wrong moment sees the old file or the new one and never half of one. That
 * costs nothing while the process lives. Killed between the write and the rename — swapped out in
 * the background, force-stopped, crashed — it leaves the temporary on the share, and the process
 * that would have cleaned it up is the one that died. Nothing else ever looked at these files:
 * readers match on exact names and skip them, so they were harmless and permanent, which over
 * months of ordinary use is a share strewn with them.
 *
 * <p>They are cleared by whoever next has the directory listed for another reason, on the rule
 * below. Never on the write path: a heartbeat that had to enumerate a directory first would pay for
 * this every twenty seconds to find nothing.
 */
final class SmbTempFiles {

    private static final String TAG = "SmbTempFiles";

    /** Only has to be a suffix nothing a gallery holds ends in; a temporary answers no question. */
    static final String SUFFIX = ".tmp";

    /**
     * How old a temporary must be before anyone may assume its writer is gone.
     *
     * <p>Far past any write that is merely slow: the state store gives up on a rename after eight
     * seconds, and a page is written in one pass. Five minutes is not a measurement, it is the
     * distance at which the question stops being close. The mistake this guards against is deleting
     * a file another device is writing this moment, which would corrupt that write; being slow to
     * remove litter costs nothing.
     */
    static final long ABANDONED_AFTER_MS = 5 * 60_000L;

    private SmbTempFiles() {
    }

    /**
     * A temporary name for {@code base}.
     *
     * <p>Unique per attempt, so two writers of the same file cannot land on each other's temporary,
     * and so one left by a killed process is never mistaken for the current one.
     */
    @NonNull
    static String nameFor(@NonNull String base) {
        return base + "." + System.nanoTime() + SUFFIX;
    }

    /**
     * Whether a file in a share directory is a temporary nobody is coming back for.
     *
     * <p>Both clocks are arguments so the rule can be stated in a test rather than waited out.
     *
     * <p>An mtime the share reports as zero, or as being in the future, leaves the file alone. Both
     * mean this device cannot tell how old it is — an unusable timestamp, or a server whose clock
     * disagrees with ours — and the safe answer to "is anyone still writing this?" is yes.
     */
    static boolean isAbandoned(@NonNull String name, long mtimeMillis, long nowMillis) {
        if (!name.endsWith(SUFFIX)) {
            return false;
        }
        if (mtimeMillis <= 0L) {
            return false;
        }
        return nowMillis - mtimeMillis >= ABANDONED_AFTER_MS;
    }

    /**
     * Deletes the abandoned temporaries in one directory, and returns how many went.
     *
     * <p>Lists the directory itself, so call it where a listing is affordable — after a download
     * finishes, not while one is running. Where the caller already holds a listing, apply
     * {@link #isAbandoned} to it and call {@link #delete} instead.
     *
     * <p>Performs SMB I/O; call from a worker thread. Never throws: this is housekeeping, and no
     * caller of it should fail over litter it could not remove.
     */
    static int sweep(@Nullable SmbFile dir, long nowMillis) {
        if (dir == null) {
            return 0;
        }
        int removed = 0;
        try {
            SmbFile[] children = dir.listFiles();
            if (children == null) {
                return 0;
            }
            for (SmbFile child : children) {
                try {
                    if (isAbandoned(child.getName(), child.lastModified(), nowMillis)) {
                        removed += delete(child) ? 1 : 0;
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "Could not examine " + child.getName(), e);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Could not sweep " + dir, e);
        }
        return removed;
    }

    /**
     * Removes one temporary. Returns whether it is gone.
     *
     * <p>Losing a race to delete is the expected outcome, not an error: {@code readAll} is called
     * from more than one place at a time — the download list refreshing and the downloader
     * reconciling with the share both do it — so two threads routinely reach the same abandoned
     * file within a millisecond of each other and one of them is told there is no such file. That
     * was observed on the real share the first time this ran. It is a success from where the caller
     * stands, so it is reported as one, and only a delete that leaves the file behind is worth
     * anyone's attention.
     */
    static boolean delete(@NonNull SmbFile file) {
        try {
            file.delete();
            Log.i(TAG, "Removed abandoned temporary " + file.getName());
            return true;
        } catch (Throwable e) {
            try {
                if (!file.exists()) {
                    return true;
                }
            } catch (Throwable ignored) {
                // Cannot even ask. Fall through and report the original failure.
            }
            // The share said no. The next pass tries again; until then it is one stale file.
            Log.w(TAG, "Could not remove " + file.getName(), e);
            return false;
        }
    }
}
