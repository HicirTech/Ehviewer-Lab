package com.hippo.ehviewer.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;

import com.hippo.ehviewer.storage.DownloadState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The state/ vocabulary; everything here is pure: no IO, no Android, no clock. */
public final class DownloadState {

    /** Bump on an incompatible change; a newer file must be ignored, never overwritten. */
    public static final int SCHEMA_VERSION = 1;

    /** Silent this long = orphaned; 4.5 heartbeats, so a WiFi blip does not orphan a download. */
    public static final long STALE_AFTER_MS = 90_000L;

    public static final class Task {
        public final long gid;
        @Nullable public final String token;
        @Nullable public final String title;
        public final int finished;
        public final int total;
        /** By the owner's clock: compare it only with other claims. */
        public final long claimedAt;
        @Nullable public final String takenOverFrom;

        public Task(long gid, @Nullable String token, @Nullable String title,
                    int finished, int total,
                    long claimedAt, @Nullable String takenOverFrom) {
            this.gid = gid;
            this.token = token;
            this.title = title;
            this.finished = finished;
            this.total = total;
            this.claimedAt = claimedAt;
            this.takenOverFrom = takenOverFrom;
        }
    }

    public static final class ClientState {
        public final int schemaVersion;
        @NonNull public final String clientId;
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

        public boolean isReadable() {
            return schemaVersion <= SCHEMA_VERSION;
        }
    }

    public static final class OwnedTask {
        @NonNull public final Task task;
        @NonNull public final String clientId;
        @NonNull public final String deviceName;
        public final boolean ownerAlive;
        public final long lastSeenMillis;

        OwnedTask(@NonNull Task task, @NonNull String clientId, @NonNull String deviceName,
                  boolean ownerAlive, long lastSeenMillis) {
            this.task = task;
            this.clientId = clientId;
            this.deviceName = deviceName;
            this.ownerAlive = ownerAlive;
            this.lastSeenMillis = lastSeenMillis;
        }

        public boolean isActionableBy(@NonNull String viewerClientId) {
            return clientId.equals(viewerClientId);
        }

        public boolean isTakeOverableBy(@NonNull String viewerClientId) {
            return !clientId.equals(viewerClientId) && !ownerAlive;
        }
    }

    public static final class Published {
        @NonNull public final ClientState state;
        public final boolean alive;
        /** The file's mtime: the owner's last heartbeat. */
        public final long lastSeenMillis;

        public Published(@NonNull ClientState state, boolean alive, long lastSeenMillis) {
            this.state = state;
            this.alive = alive;
            this.lastSeenMillis = lastSeenMillis;
        }
    }

    private DownloadState() {}

    /** Liveness beats claim time: a dead device's clock must not hold a gallery hostage. */
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
        return inDisplayOrder(new ArrayList<>(best.values()));
    }

    /** Heads sort by device name so heartbeats do not reshuffle rows. */
    @NonNull
    private static List<OwnedTask> inDisplayOrder(@NonNull List<OwnedTask> tasks) {
        final Map<String, Long> headClaimByClient = new LinkedHashMap<>();
        for (OwnedTask o : tasks) {
            Long current = headClaimByClient.get(o.clientId);
            if (current == null || o.task.claimedAt < current) {
                headClaimByClient.put(o.clientId, o.task.claimedAt);
            }
        }
        List<OwnedTask> heads = new ArrayList<>();
        List<OwnedTask> rest = new ArrayList<>();
        for (OwnedTask o : tasks) {
            Long head = headClaimByClient.get(o.clientId);
            if (head != null && head == o.task.claimedAt && !containsClient(heads, o.clientId)) {
                heads.add(o);
            } else {
                rest.add(o);
            }
        }
        Collections.sort(heads, new Comparator<OwnedTask>() {
            @Override
            public int compare(OwnedTask a, OwnedTask b) {
                int byName = a.deviceName.compareToIgnoreCase(b.deviceName);
                return byName != 0 ? byName : Long.compare(a.task.claimedAt, b.task.claimedAt);
            }
        });
        Collections.sort(rest, new Comparator<OwnedTask>() {
            @Override
            public int compare(OwnedTask a, OwnedTask b) {
                int byClaim = Long.compare(a.task.claimedAt, b.task.claimedAt);
                return byClaim != 0 ? byClaim : Long.compare(a.task.gid, b.task.gid);
            }
        });
        heads.addAll(rest);
        return heads;
    }

    /** Claim times can tie; only a device's first task is its head. */
    private static boolean containsClient(@NonNull List<OwnedTask> tasks, @NonNull String clientId) {
        for (OwnedTask o : tasks) {
            if (o.clientId.equals(clientId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean wins(@NonNull OwnedTask candidate, @NonNull OwnedTask current) {
        if (candidate.ownerAlive != current.ownerAlive) {
            return candidate.ownerAlive;
        }
        return candidate.task.claimedAt > current.task.claimedAt;
    }

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

    /** Drops what a live client claimed later, so takeover duplicates stay transient. */
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

    public static final class ReconcilePlan {
        /** Claimed later by a live device: stand down without touching the share. */
        @NonNull public final List<Long> yields;
        /** Published by this device but no longer held: bring back as paused. */
        @NonNull public final List<Task> restores;
        /** Our file may still advertise stale claims: republish it. */
        public final boolean shouldPublish;

        ReconcilePlan(@NonNull List<Long> yields, @NonNull List<Task> restores,
                      boolean shouldPublish) {
            this.yields = yields;
            this.restores = restores;
            this.shouldPublish = shouldPublish;
        }
    }

    /** Retired = finished with by this process, so never restored. */
    public interface RetiredCheck {
        boolean isRetired(long gid);
    }

    @NonNull
    public static ReconcilePlan planReconcile(@NonNull String selfId,
                                              @NonNull ClientState held,
                                              @NonNull List<Published> all,
                                              @NonNull RetiredCheck retired) {
        List<OwnedTask> merged = merge(all);

        List<Long> stillOurs = new ArrayList<>();
        for (Task t : withoutTakenOver(held, merged)) {
            stillOurs.add(t.gid);
        }
        List<Long> yields = new ArrayList<>();
        for (Task t : held.tasks) {
            if (!stillOurs.contains(t.gid)) {
                yields.add(t.gid);
            }
        }

        ClientState published = null;
        for (Published p : all) {
            if (p.state.clientId.equals(selfId)) {
                published = p.state;
                break;
            }
        }
        if (published == null) {
            return new ReconcilePlan(yields, new ArrayList<>(), false);
        }
        List<Long> heldGids = new ArrayList<>();
        for (Task t : held.tasks) {
            heldGids.add(t.gid);
        }
        List<Task> restores = new ArrayList<>();
        for (Task t : withoutTakenOver(published, merged)) {
            if (!heldGids.contains(t.gid) && !retired.isRetired(t.gid)) {
                restores.add(t);
            }
        }
        return new ReconcilePlan(yields, restores, restores.isEmpty());
    }

    /** What a takeover finds on a fresh look just before acting. */
    public enum TakeOverAssessment {
        /** Report it as taken; change nothing. */
        ALREADY_OURS,
        OWNER_ALIVE,
        ORPHAN
    }

    @NonNull
    public static TakeOverAssessment assessTakeOver(@NonNull List<OwnedTask> merged,
                                                    long gid, @NonNull String selfId) {
        for (OwnedTask o : merged) {
            if (o.task.gid != gid) {
                continue;
            }
            if (o.clientId.equals(selfId)) {
                return TakeOverAssessment.ALREADY_OURS;
            }
            if (o.ownerAlive) {
                return TakeOverAssessment.OWNER_ALIVE;
            }
            break;
        }
        return TakeOverAssessment.ORPHAN;
    }

    /** Null, never an exception, so one corrupt file cannot blind the list. */
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

    /** Indented: the file is meant to be readable on the NAS. */
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
