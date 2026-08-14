package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;

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
        return SmbPaths.buildStateRootUrl(SmbConnection.buildSmbUrl());
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

    /**
     * Clears one leftover of an interrupted publish, if it has been there long enough to be one.
     *
     * <p>The temporary belongs to another device as often as not, which everywhere else in this
     * class would be forbidden — a device's file is its own. The exception holds because of what
     * the age means: a publish gives up on its rename after {@link #WRITE_DEADLINE_MS} and deletes
     * its own temporary, so one that has sat for {@link SmbTempFiles#ABANDONED_AFTER_MS} is not a
     * write in progress. Its writer is gone, and it is the one thing on the share nobody will ever
     * read.
     *
     * <p>Done from the read because that is where the directory is already listed. Nobody makes a
     * trip for this.
     */
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
        return nowMillis - mtimeMillis < STALE_AFTER_MS;
    }

    // ------------------------------------------------------------------ write

    /**
     * Publishes this device's state, replacing whatever it last wrote.
     *
     * <p>Retries because another device holding the file open blocks the rename until it lets go.
     *
     * @return whether the state reached the share.
     */
    public static boolean writeSelf(@NonNull ClientState state) {
        if (!SmbConnection.isConfigured()) {
            return false;
        }
        try {
            SmbFile dir = new SmbFile(stateRootUrl(), SmbConnection.buildContext());
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return writeTo(dir, state.clientId, SmbDownloadState.serialize(state));
        } catch (Throwable e) {
            Log.e(TAG, "Failed to publish client state", e);
            return false;
        }
    }

    /**
     * Puts {@code json} at {@code <clientId>.json}, atomically as far as any reader can tell.
     *
     * <p>Written to a temporary name and renamed over the target, so a device reading at the wrong
     * moment sees either the old file or the new one and never a half-written one. The two-argument
     * rename is required: the single-argument form refuses an existing target.
     */
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
     * Takes one gallery out of another device's published queue, for a takeover.
     *
     * <p><b>The single exception to "only its owner writes a file", and it is narrow.</b> The
     * caller must have established that the owner's heartbeat has been stale for
     * {@link #STALE_AFTER_MS} — which is exactly the condition that says nobody else is writing
     * this file. Leaving the entry instead was the alternative, and it is worse: the abandoned copy
     * would resurface the moment the device that rescued the gallery stopped claiming it, and if
     * the original never came back it would sit there for good.
     *
     * <p>Read-modify-write, so a device that woke up between the two and queued something new could
     * lose that entry. It is a narrow window against a device that has said nothing for a minute
     * and a half, and the cost of losing is one queue entry rather than any downloaded pages.
     *
     * <p>Performs SMB I/O; call from a worker thread.
     *
     * @return whether the file is now free of the gallery — including when it never had it.
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
            ClientState state = SmbDownloadState.parse(readAll(file));
            if (state == null || !state.isReadable()) {
                // Unreadable, or written by a build that knows more than this one. Rewriting it
                // from what we managed to understand would throw away whatever we did not.
                Log.w(TAG, "Not editing a state file this build cannot read: " + ownerClientId);
                return false;
            }
            List<SmbDownloadState.Task> kept = new ArrayList<>(state.tasks.size());
            for (SmbDownloadState.Task t : state.tasks) {
                if (t.gid != gid) {
                    kept.add(t);
                }
            }
            if (kept.size() == state.tasks.size()) {
                return true;   // already gone
            }
            return writeTo(dir, ownerClientId, SmbDownloadState.serialize(
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
