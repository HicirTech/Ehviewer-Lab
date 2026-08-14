package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import jcifs.smb.NtStatus;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

/**
 * Abandoned atomic-write temporaries and who clears them (#75): whoever next holds a listing
 * anyway — never the write path.
 */
final class SmbTempFiles {

    private static final String TAG = "SmbTempFiles";

    /** Only has to be a suffix nothing a gallery holds ends in; a temporary answers no question. */
    static final String SUFFIX = ".tmp";

    // Far past any merely-slow write; deleting a live write corrupts it, slow littering costs nothing.
    static final long ABANDONED_AFTER_MS = 5 * 60_000L;

    private SmbTempFiles() {
    }

    /** Unique per attempt, so concurrent writers and dead processes cannot collide. */
    @NonNull
    static String nameFor(@NonNull String base) {
        return base + "." + System.nanoTime() + SUFFIX;
    }

    /** Abandoned? Clocks are arguments (testable); zero/future mtimes read as "still writing". */
    static boolean isAbandoned(@NonNull String name, long mtimeMillis, long nowMillis) {
        if (!name.endsWith(SUFFIX)) {
            return false;
        }
        if (mtimeMillis <= 0L) {
            return false;
        }
        return nowMillis - mtimeMillis >= ABANDONED_AFTER_MS;
    }

    /** Sweeps one directory (lists it — call where a listing is affordable). Never throws. */
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

    /** Deletes one temporary; losing the race to another sweeper counts as success. */
    static boolean delete(@NonNull SmbFile file) {
        try {
            file.delete();
            Log.i(TAG, "Removed abandoned temporary " + file.getName());
            return true;
        } catch (Throwable e) {
            if (alreadyGone(e)) {
                return true;
            }
            // The share said no. The next pass tries again; until then it is one stale file.
            Log.w(TAG, "Could not remove " + file.getName(), e);
            return false;
        }
    }

    /** From the exception, not exists() — enumeration-born SmbFiles report stale attributes. */
    private static boolean alreadyGone(@NonNull Throwable e) {
        if (!(e instanceof SmbException)) {
            return false;
        }
        int status = ((SmbException) e).getNtStatus();
        return status == NtStatus.NT_STATUS_NO_SUCH_FILE
                || status == NtStatus.NT_STATUS_OBJECT_NAME_NOT_FOUND
                || status == NtStatus.NT_STATUS_OBJECT_PATH_NOT_FOUND;
    }
}
