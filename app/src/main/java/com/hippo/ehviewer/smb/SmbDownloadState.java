package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one device publishes about its SMB downloads, and how several devices' publications combine
 * into the single list a user sees.
 *
 * <p>The share is a space several clients read and write, so download state belongs on it rather
 * than in a local database: it survives the process, it lets one device see what another is doing,
 * and that visibility is what stops two devices downloading the same gallery. The layout is one
 * JSON file per client under {@code state/}, and <b>only its owner ever writes it</b>. That is what
 * keeps the common path free of locking — measurements on a real share put a lock-read-modify-write
 * cycle at 129 ms against 64 ms for a plain write, and the shared-file design would have paid the
 * former on every heartbeat.
 *
 * <p>The file's own mtime is the heartbeat; nothing here records a timestamp for liveness. Freshness
 * is therefore something only the reader of the directory can determine, which is why
 * {@link #merge} takes it as an argument rather than reading a clock.
 *
 * <p>Everything in this class is a pure function of its arguments — no SMB, no Android, no clock —
 * so the merge and claim rules can be tested without a share.
 */
public final class SmbDownloadState {

    /**
     * Bumped when the on-share shape changes incompatibly. A client that meets a version it does
     * not understand ignores the file rather than guessing, and must not overwrite it.
     */
    public static final int SCHEMA_VERSION = 1;

    /** Where a gallery is in its owner's queue. */
    public enum TaskState {
        QUEUED,
        ACTIVE,
        PAUSED;

        /**
         * Where this state sorts in the merged list: running work, then waiting, then held.
         *
         * <p>Spelled out rather than taken from {@code ordinal()}, so that the order things appear
         * in does not silently depend on the order they happen to be declared in — and so adding a
         * state later is a decision about where it belongs rather than an accident.
         */
        int displayRank() {
            switch (this) {
                case ACTIVE: return 0;
                case QUEUED: return 1;
                case PAUSED: return 2;
                default: return 3;
            }
        }

        @NonNull
        static TaskState parse(@Nullable String raw) {
            if (raw != null) {
                for (TaskState s : values()) {
                    if (s.name().equalsIgnoreCase(raw)) {
                        return s;
                    }
                }
            }
            return QUEUED;
        }
    }

    /** One gallery on one device's list. */
    public static final class Task {
        public final long gid;
        @Nullable public final String token;
        @Nullable public final String title;
        @NonNull public final TaskState state;
        public final int finished;
        public final int total;
        /**
         * When this device took the task on, in epoch millis by that device's clock. Only ever
         * compared against another device's claim on the same gallery, and never against the
         * reader's own clock — see {@link #merge}.
         */
        public final long claimedAt;
        /** The client this was taken over from, or null if it was never anyone else's. */
        @Nullable public final String takenOverFrom;

        public Task(long gid, @Nullable String token, @Nullable String title,
                    @NonNull TaskState state, int finished, int total,
                    long claimedAt, @Nullable String takenOverFrom) {
            this.gid = gid;
            this.token = token;
            this.title = title;
            this.state = state;
            this.finished = finished;
            this.total = total;
            this.claimedAt = claimedAt;
            this.takenOverFrom = takenOverFrom;
        }
    }

    /** One client's whole published state: the contents of a single {@code state/<uuid>.json}. */
    public static final class ClientState {
        public final int schemaVersion;
        @NonNull public final String clientId;
        /** What to show a human. Defaults to the device model; the user may rename it. */
        @NonNull public final String deviceName;
        @NonNull public final List<Task> tasks;

        public ClientState(int schemaVersion, @NonNull String clientId, @NonNull String deviceName,
                           @NonNull List<Task> tasks) {
            this.schemaVersion = schemaVersion;
            this.clientId = clientId;
            this.deviceName = deviceName;
            this.tasks = tasks;
        }

        public ClientState(@NonNull String clientId, @NonNull String deviceName,
                           @NonNull List<Task> tasks) {
            this(SCHEMA_VERSION, clientId, deviceName, tasks);
        }

        /** Whether this file is one we know how to read. */
        public boolean isReadable() {
            return schemaVersion <= SCHEMA_VERSION;
        }
    }

    /** A task paired with who published it — what the merged list is made of. */
    public static final class OwnedTask {
        @NonNull public final Task task;
        @NonNull public final String clientId;
        @NonNull public final String deviceName;
        /** False once the owner's file has gone stale; only then may another device take over. */
        public final boolean ownerAlive;
        /** When the owner last wrote its file, by the share's clock. Zero if unknown. */
        public final long lastSeenMillis;

        OwnedTask(@NonNull Task task, @NonNull String clientId, @NonNull String deviceName,
                  boolean ownerAlive, long lastSeenMillis) {
            this.task = task;
            this.clientId = clientId;
            this.deviceName = deviceName;
            this.ownerAlive = ownerAlive;
            this.lastSeenMillis = lastSeenMillis;
        }

        /**
         * Whether this device may pause, resume or delete the task.
         *
         * <p>Only its own. Another device's is not ours to stop — the decision lives in the process
         * doing the work, which would carry on regardless — and not ours to delete either, since
         * removing it from the list means editing a file this device must never write. An orphan is
         * not an exception to this: it has to be adopted first, and then it is simply ours.
         */
        public boolean isActionableBy(@NonNull String viewerClientId) {
            return clientId.equals(viewerClientId);
        }

        /**
         * Whether this device may adopt the task.
         *
         * <p>The one thing anybody may do to somebody else's download, and only once that somebody
         * has stopped saying it is still there. Without it a device that died mid-download would
         * take its queue with it, and nothing on the share could ever pick the work back up.
         */
        public boolean isTakeOverableBy(@NonNull String viewerClientId) {
            return !clientId.equals(viewerClientId) && !ownerAlive;
        }
    }

    /** A client's file plus the liveness the directory listing established for it. */
    public static final class Published {
        @NonNull final ClientState state;
        final boolean alive;
        /**
         * The file's mtime — the heartbeat itself, carried through so the list can say how long
         * ago another device was last heard from. A user deciding whether to take a download over
         * needs that far more than the yes/no {@link #alive} the same number produced.
         */
        final long lastSeenMillis;

        public Published(@NonNull ClientState state, boolean alive, long lastSeenMillis) {
            this.state = state;
            this.alive = alive;
            this.lastSeenMillis = lastSeenMillis;
        }
    }

    private SmbDownloadState() {}

    // ------------------------------------------------------------------ merge

    /**
     * Combines every client's published state into one list, at most one entry per gallery.
     *
     * <p>Two devices can name the same gallery: a takeover writes the claim into the claiming
     * device's own file and leaves the original alone, so until the original device next comes
     * online and drops its stale entry, both files mention it. The rule that resolves this is
     * <b>a live owner beats a dead one</b>, and only when both are equally alive does the later
     * {@code claimedAt} win.
     *
     * <p>That ordering matters more than it looks. Comparing timestamps first would let a dead
     * device's clock — which may be wrong, and which nobody can correct — keep a gallery hostage
     * from the device actually holding it. Liveness comes from file mtime, which the reader
     * observes itself, so it is the trustworthy half of the comparison.
     *
     * <p>Files whose schema is newer than this build understands are skipped entirely.
     */
    @NonNull
    public static List<OwnedTask> merge(@NonNull Collection<Published> published) {
        Map<Long, OwnedTask> best = new LinkedHashMap<>();
        for (Published p : published) {
            if (!p.state.isReadable()) {
                continue;
            }
            for (Task t : p.state.tasks) {
                OwnedTask candidate = new OwnedTask(
                        t, p.state.clientId, p.state.deviceName, p.alive, p.lastSeenMillis);
                OwnedTask current = best.get(t.gid);
                if (current == null || wins(candidate, current)) {
                    best.put(t.gid, candidate);
                }
            }
        }
        List<OwnedTask> out = new ArrayList<>(best.values());
        // Active work first, then what is waiting, then what is held — and inside each, the most
        // recently claimed first, so a list several devices contribute to still reads
        // chronologically. Collections.sort and a hand-written comparator rather than
        // List.sort/Comparator.comparingInt: those are API 24, and minSdk here is 23 with no core
        // library desugaring.
        Collections.sort(out, new Comparator<OwnedTask>() {
            @Override
            public int compare(OwnedTask a, OwnedTask b) {
                int byState = a.task.state.displayRank() - b.task.state.displayRank();
                if (byState != 0) {
                    return byState;
                }
                return Long.compare(b.task.claimedAt, a.task.claimedAt);
            }
        });
        return out;
    }

    private static boolean wins(@NonNull OwnedTask candidate, @NonNull OwnedTask current) {
        if (candidate.ownerAlive != current.ownerAlive) {
            return candidate.ownerAlive;
        }
        return candidate.task.claimedAt > current.task.claimedAt;
    }

    /**
     * Whether this device should skip enqueuing a gallery because another one already has it.
     *
     * <p>Its own entries do not block it — re-enqueuing something already queued here is handled by
     * the downloader — and neither does an orphan, which is the whole point of being able to take
     * one over.
     */
    public static boolean isClaimedByAnotherLiveClient(@NonNull List<OwnedTask> merged,
                                                       long gid,
                                                       @NonNull String selfClientId) {
        for (OwnedTask o : merged) {
            if (o.task.gid == gid) {
                return o.ownerAlive && !o.clientId.equals(selfClientId);
            }
        }
        return false;
    }

    /**
     * Drops entries this device no longer owns, for use when it comes back online.
     *
     * <p>A device that went away may have had its tasks taken over. It cannot be told directly —
     * nobody writes to another device's file — so it works it out on its next read: anything of
     * its own that a live client now claims more recently is no longer its business, and it removes
     * it from its own file. This is what keeps the duplicate visible after a takeover transient
     * rather than permanent.
     */
    @NonNull
    public static List<Task> withoutTakenOver(@NonNull ClientState self,
                                              @NonNull List<OwnedTask> merged) {
        List<Task> kept = new ArrayList<>(self.tasks.size());
        for (Task t : self.tasks) {
            boolean lost = false;
            for (OwnedTask o : merged) {
                if (o.task.gid == t.gid
                        && !o.clientId.equals(self.clientId)
                        && o.ownerAlive
                        && o.task.claimedAt > t.claimedAt) {
                    lost = true;
                    break;
                }
            }
            if (!lost) {
                kept.add(t);
            }
        }
        return kept;
    }

    // ------------------------------------------------------------------ json

    /**
     * Parses one {@code state/<uuid>.json}. Returns null for anything unreadable rather than
     * throwing: one corrupt or half-written file must not take the whole list down, and the worst
     * case of ignoring it is that its owner looks idle.
     */
    @Nullable
    public static ClientState parse(@Nullable String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONObject root = JSONObject.parseObject(json);
            if (root == null) {
                return null;
            }
            String clientId = root.getString("clientId");
            if (clientId == null || clientId.isEmpty()) {
                return null;
            }
            Integer version = root.getInteger("schemaVersion");
            String deviceName = root.getString("deviceName");
            List<Task> tasks = new ArrayList<>();
            JSONArray arr = root.getJSONArray("tasks");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    Long gid = o.getLong("gid");
                    if (gid == null) {
                        continue;
                    }
                    tasks.add(new Task(
                            gid,
                            o.getString("token"),
                            o.getString("title"),
                            TaskState.parse(o.getString("state")),
                            intOr(o.getInteger("finished"), 0),
                            intOr(o.getInteger("total"), 0),
                            longOr(o.getLong("claimedAt"), 0L),
                            o.getString("takenOverFrom")));
                }
            }
            return new ClientState(
                    version == null ? SCHEMA_VERSION : version,
                    clientId,
                    deviceName == null || deviceName.isEmpty() ? clientId : deviceName,
                    tasks);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Serialises one client's state.
     *
     * <p>Indented, and with keys in insertion order, because being able to open these on the NAS
     * and read them is the reason the format is plain JSON in the first place. The files are a few
     * hundred bytes, so the whitespace costs nothing worth counting.
     */
    @NonNull
    public static String serialize(@NonNull ClientState state) {
        JSONObject root = new JSONObject(true);
        root.put("schemaVersion", state.schemaVersion);
        root.put("clientId", state.clientId);
        root.put("deviceName", state.deviceName);
        JSONArray arr = new JSONArray();
        for (Task t : state.tasks) {
            JSONObject o = new JSONObject(true);
            o.put("gid", t.gid);
            if (t.token != null) o.put("token", t.token);
            if (t.title != null) o.put("title", t.title);
            o.put("state", t.state.name());
            o.put("finished", t.finished);
            o.put("total", t.total);
            o.put("claimedAt", t.claimedAt);
            if (t.takenOverFrom != null) o.put("takenOverFrom", t.takenOverFrom);
            arr.add(o);
        }
        root.put("tasks", arr);
        return JSON.toJSONString(root, SerializerFeature.PrettyFormat);
    }

    private static int intOr(@Nullable Integer v, int fallback) {
        return v == null ? fallback : v;
    }

    private static long longOr(@Nullable Long v, long fallback) {
        return v == null ? fallback : v;
    }
}
