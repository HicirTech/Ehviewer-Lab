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

    private static void sweepIfAbandoned(@NonNull SmbFile child, long nowMillis) {
        try {
            if (SmbTempFiles.isAbandoned(child.getName(), child.lastModified(), nowMillis)) {
                SmbTempFiles.delete(child);
            }
        } catch (Throwable e) {
            // Housekeeping must not fail the read.
            Log.w(TAG, "Could not examine " + child.getName(), e);
        }
    }

    static boolean isAlive(long mtimeMillis, long nowMillis) {
        if (mtimeMillis <= 0L) {
            // Unknown age counts as dead: alive would strand its tasks for good.
            return false;
        }
        // A future mtime is clock skew, so alive: never take the absolute difference.
        return nowMillis - mtimeMillis < DownloadState.STALE_AFTER_MS;
    }

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
        SmbTempFiles.delete(tempFile);
        return false;
    }

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
                // Rewriting from a partial reading would drop what a newer build wrote.
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
                return true;
            }
            return writeTo(dir, ownerClientId, DownloadState.serialize(
                    new ClientState(state.schemaVersion, state.clientId, state.deviceName, kept)));
        } catch (Throwable e) {
            Log.w(TAG, "Could not remove gid=" + gid + " from " + ownerClientId, e);
            return false;
        }
    }

    private static String readAll(SmbFile file) throws Exception {
        // Close fast: while open, this handle blocks the owner's rename over the file.
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
