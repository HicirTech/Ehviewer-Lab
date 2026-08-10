package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.smb.SmbDownloadState.ClientState;
import com.hippo.ehviewer.smb.SmbDownloadState.Published;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;

/**
 * Reads and writes the {@code state/} directory where devices publish their SMB downloads (#59).
 *
 * <p>Kept apart from {@link SmbStorage} for the same reason {@link SmbPaths} and {@link SmbMetadata}
 * are: that class is already long, and the rules this one implements are worth being able to read
 * on their own.
 *
 * <p><b>There is no lock anywhere here, and that is deliberate.</b> Each device writes exactly one
 * file — its own — so the only concurrency left is readers, which cannot conflict with each other.
 * The spike measured a lock-read-modify-write cycle at 129 ms against 64 ms for a plain write, and
 * a design where every heartbeat took a lock would have made frequent progress updates unaffordable.
 *
 * <p>What does remain is that an open read handle blocks a rename with {@code STATUS_ACCESS_DENIED}
 * until it closes. So writes retry with backoff, and reads open, read and close as fast as they can.
 */
public final class SmbDownloadStateStore {

    private static final String TAG = "SmbDownloadState";
    private static final String SUFFIX = ".json";

    /**
     * How long a client's file may go untouched before other devices treat its tasks as orphaned
     * and offer to take them over.
     *
     * <p>Its mtime is the heartbeat, and a downloading device rewrites it every few seconds, so
     * this is many missed beats rather than a couple — long enough that a lock-screen pause or a
     * WiFi blip does not hand someone else's download away, short enough that a device that really
     * did crash releases its work in about the time it takes to notice.
     */
    public static final long STALE_AFTER_MS = 90_000L;

    /**
     * How long to keep trying to publish before giving up.
     *
     * <p>The failure being retried is another device reading this file at the moment of the rename.
     * The spike had a writer through in 13 attempts over 1918 ms against a reader that held on for
     * 1.5 s, so this leaves room for a considerably slower one. Giving up is not serious: the next
     * heartbeat will carry the same state.
     */
    private static final long WRITE_DEADLINE_MS = 8_000L;
    private static final long WRITE_BACKOFF_START_MS = 100L;
    private static final long WRITE_BACKOFF_MAX_MS = 800L;

    private SmbDownloadStateStore() {}

    @NonNull
    private static String stateRootUrl() {
        return SmbPaths.buildStateRootUrl(SmbStorage.buildSmbUrl());
    }

    // ------------------------------------------------------------------ read

    /**
     * Reads every device's published state.
     *
     * <p>Liveness is decided here, against this device's clock, because mtime is the only evidence
     * of it and only the reader of the directory can see it. A file dated in the future is taken as
     * alive rather than as impossibly stale: that means the server and this device disagree about
     * the time, and treating a running device's work as abandoned is far worse than being slow to
     * reclaim a dead one's.
     *
     * <p>Unreadable files are skipped rather than fatal — one device writing garbage, or caught
     * mid-write, must not blind this one to all the others.
     *
     * <p>Performs SMB I/O; call from a worker thread.
     */
    @NonNull
    public static List<Published> readAll() {
        List<Published> out = new ArrayList<>();
        if (!SmbStorage.isConfigured()) {
            return out;
        }
        try {
            CIFSContext cifs = SmbStorage.buildContext();
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
                    // .tmp files from a write in flight, and anything else that wanders in.
                    continue;
                }
                try {
                    long mtime = child.lastModified();
                    String json = readAll(child);
                    ClientState state = SmbDownloadState.parse(json);
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
        return nowMillis - mtimeMillis < STALE_AFTER_MS;
    }

    // ------------------------------------------------------------------ write

    /**
     * Publishes this device's state, replacing whatever it last wrote.
     *
     * <p>Written to a temporary name and renamed over the target, so a device reading at the wrong
     * moment sees either the old file or the new one and never a half-written one. The two-argument
     * rename is required: the single-argument form refuses an existing target.
     *
     * <p>Retries because another device holding the file open blocks the rename until it lets go.
     *
     * @return whether the state reached the share.
     */
    public static boolean writeSelf(@NonNull ClientState state) {
        if (!SmbStorage.isConfigured()) {
            return false;
        }
        String json = SmbDownloadState.serialize(state);
        String target = state.clientId + SUFFIX;
        // Unique per attempt-run so two writes from this device — which should not overlap, but
        // might if a heartbeat and a state change race — cannot land on each other's temp file.
        String temp = state.clientId + "." + System.nanoTime() + ".tmp";

        try {
            CIFSContext cifs = SmbStorage.buildContext();
            SmbFile dir = new SmbFile(stateRootUrl(), cifs);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            SmbFile tempFile = new SmbFile(dir, temp);
            try (OutputStream os = new java.io.BufferedOutputStream(
                    tempFile.getOutputStream(), SmbStorage.SMB_IO_BUFFER)) {
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
            Log.w(TAG, "Gave up publishing state after " + WRITE_DEADLINE_MS + "ms", last);
            try {
                tempFile.delete();
            } catch (Throwable ignored) {
                // A leftover .tmp is skipped by readAll and overwritten next time.
            }
            return false;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to publish client state", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static String readAll(SmbFile file) throws Exception {
        // Open, read, close, as briefly as possible: while this handle is open, the owning device
        // cannot rename its new file over this one.
        try (InputStream is = new java.io.BufferedInputStream(
                file.getInputStream(), SmbStorage.SMB_IO_BUFFER)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[SmbStorage.SMB_IO_BUFFER];
            int read;
            while ((read = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
