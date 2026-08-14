package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;


import com.hippo.ehviewer.storage.DownloadState;
import com.hippo.ehviewer.storage.DownloadState.ClientState;
import com.hippo.ehviewer.storage.DownloadState.Published;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

/**
 * File IO for state/ (#59). Deliberately lock-free: each device writes only its own file (a
 * locked cycle measured 129ms vs 64ms). Open read handles block renames, so writes retry with
 * backoff and reads close fast.
 */
public final class SmbDownloadStateStore {

    private static final String TAG = "DownloadState";
    private static final String SUFFIX = ".json";


    // Retrying readers' open handles; measured through in ~2s. Giving up is fine: next beat retries.
    private static final long WRITE_DEADLINE_MS = 8_000L;
    private static final long WRITE_BACKOFF_START_MS = 100L;
    private static final long WRITE_BACKOFF_MAX_MS = 800L;

    private SmbDownloadStateStore() {}

    @NonNull
    private static String stateRootUrl() {
        return SmbPaths.buildStateRootUrl(SmbConnection.buildSmbUrl());
    }

    // ------------------------------------------------------------------ read

    /**
     * Every device's state; liveness decided here from mtime (future-dated = alive, clock skew).
     * Unreadable files are skipped. SMB I/O; worker thread.
     */
    @NonNull
    public static List<Published> readAll() {
        List<Published> out = new ArrayList<>();
        if (!SmbConnection.isConfigured()) {
            return out;
        }
        try {
            CIFSContext cifs = SmbConnection.buildContext();
            SmbFile dir = new SmbFile(stateRootUrl(), cifs);
            if (!dir.exists() || !dir.isDirectory()) {
                // Nothing has published yet. Not an error: the directory is created by the first
                // write, so its absence simply means no device has started downloading.
                return out;
            }
            SmbFile[] children = dir.listFiles();
            if (children == null) {
                return out;
            }
            long now = System.currentTimeMillis();
            for (SmbFile child : children) {
                String name = child.getName();
                if (!name.endsWith(SUFFIX)) {
                    // A write in flight, or anything else that wanders in. Neither is read; the
                    // first is also cleared away once it is old enough to have been abandoned
                    // (#75), which is a thing only a device listing this directory can notice.
                    sweepIfAbandoned(child, now);
                    continue;
                }
                try {
                    long mtime = child.lastModified();
                    String json = readAll(child);
                    ClientState state = DownloadState.parse(json);
                    if (state == null) {
                        Log.w(TAG, "Ignoring unreadable client state: " + name);
                        continue;
                    }
                    out.add(new Published(state, isAlive(mtime, now), mtime));
                } catch (Throwable e) {
                    Log.w(TAG, "Could not read client state: " + name, e);
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to list " + SmbPaths.STATE_DIR + "/", e);
        }
        return out;
    }

    /** Sweeps an abandoned publish temporary (age proves its writer is gone); free, off the read. */
    private static void sweepIfAbandoned(@NonNull SmbFile child, long nowMillis) {
        try {
            if (SmbTempFiles.isAbandoned(child.getName(), child.lastModified(), nowMillis)) {
                SmbTempFiles.delete(child);
            }
        } catch (Throwable e) {
            // Housekeeping. Reading everyone's state is the job here and must not fail over it.
            Log.w(TAG, "Could not examine " + child.getName(), e);
        }
    }

    /** Package-private so the staleness rule can be exercised without a share. */
    static boolean isAlive(long mtimeMillis, long nowMillis) {
        if (mtimeMillis <= 0L) {
            // No usable timestamp. Treating it as alive would make the task permanently
            // unrecoverable, which is the worse of the two mistakes here.
            return false;
        }
        // A timestamp ahead of this device's clock falls out of the subtraction as a negative age
        // and so counts as alive, which is what we want: it means the server and this device
        // disagree about the time, not that the file is impossibly old. There is no separate branch
        // for it — an earlier draft had one and mutation testing showed it changed nothing — but
        // the behaviour is deliberate and a test holds it, so anything "tidied" into an absolute
        // difference here will fail.
        return nowMillis - mtimeMillis < DownloadState.STALE_AFTER_MS;
    }

    // ------------------------------------------------------------------ write

    /** Publishes this device's state; retries around readers' open handles. */
    public static boolean writeSelf(@NonNull ClientState state) {
        if (!SmbConnection.isConfigured()) {
            return false;
        }
        try {
            SmbFile dir = new SmbFile(stateRootUrl(), SmbConnection.buildContext());
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return writeTo(dir, state.clientId, DownloadState.serialize(state));
        } catch (Throwable e) {
            Log.e(TAG, "Failed to publish client state", e);
            return false;
        }
    }

    /** Atomic write of <clientId>.json (temp + two-arg rename). */
    private static boolean writeTo(@NonNull SmbFile dir, @NonNull String clientId,
                                   @NonNull String json) throws Exception {
        String target = clientId + SUFFIX;
        SmbFile tempFile = new SmbFile(dir, SmbTempFiles.nameFor(clientId));
        try (OutputStream os = new java.io.BufferedOutputStream(
                tempFile.getOutputStream(), SmbGalleryFiles.SMB_IO_BUFFER)) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        long deadline = System.currentTimeMillis() + WRITE_DEADLINE_MS;
        long backoff = WRITE_BACKOFF_START_MS;
        Throwable last = null;
        while (true) {
            try {
                tempFile.renameTo(new SmbFile(dir, target), true);
                return true;
            } catch (Throwable e) {
                last = e;
                if (System.currentTimeMillis() + backoff >= deadline) {
                    break;
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, WRITE_BACKOFF_MAX_MS);
            }
        }
        Log.w(TAG, "Gave up writing " + target + " after " + WRITE_DEADLINE_MS + "ms", last);
        // Giving up is the one abandonment this process is around to clean up after itself. The
        // other -- being killed outright -- is what the sweep in readAll is for.
        SmbTempFiles.delete(tempFile);
        return false;
    }

    /**
     * Removes a gallery from another device's file — the single, narrow exception to "only the
     * owner writes", valid only against an owner stale past STALE_AFTER_MS. Worker thread.
     */
    public static boolean removeTask(@NonNull String ownerClientId, long gid) {
        if (!SmbConnection.isConfigured()) {
            return false;
        }
        try {
            CIFSContext cifs = SmbConnection.buildContext();
            SmbFile dir = new SmbFile(stateRootUrl(), cifs);
            SmbFile file = new SmbFile(dir, ownerClientId + SUFFIX);
            if (!file.exists()) {
                return true;
            }
            ClientState state = DownloadState.parse(readAll(file));
            if (state == null || !state.isReadable()) {
                // Unreadable, or written by a build that knows more than this one. Rewriting it
                // from what we managed to understand would throw away whatever we did not.
                Log.w(TAG, "Not editing a state file this build cannot read: " + ownerClientId);
                return false;
            }
            List<DownloadState.Task> kept = new ArrayList<>(state.tasks.size());
            for (DownloadState.Task t : state.tasks) {
                if (t.gid != gid) {
                    kept.add(t);
                }
            }
            if (kept.size() == state.tasks.size()) {
                return true;   // already gone
            }
            return writeTo(dir, ownerClientId, DownloadState.serialize(
                    new ClientState(state.schemaVersion, state.clientId, state.deviceName, kept)));
        } catch (Throwable e) {
            Log.w(TAG, "Could not remove gid=" + gid + " from " + ownerClientId, e);
            return false;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static String readAll(SmbFile file) throws Exception {
        // Open, read, close, as briefly as possible: while this handle is open, the owning device
        // cannot rename its new file over this one.
        try (InputStream is = new java.io.BufferedInputStream(
                file.getInputStream(), SmbGalleryFiles.SMB_IO_BUFFER)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[SmbGalleryFiles.SMB_IO_BUFFER];
            int read;
            while ((read = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
