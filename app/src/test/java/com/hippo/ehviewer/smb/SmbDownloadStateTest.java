package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.smb.SmbDownloadState.ClientState;
import com.hippo.ehviewer.smb.SmbDownloadState.OwnedTask;
import com.hippo.ehviewer.smb.SmbDownloadState.Published;
import com.hippo.ehviewer.smb.SmbDownloadState.Task;
import com.hippo.ehviewer.smb.SmbDownloadState.TaskState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The rules by which several devices' published download state becomes one list.
 *
 * <p>This is the part of #59 that has to be right without a share to try it on: every client writes
 * only its own file, so the single view a user sees exists nowhere on disk — it is computed here,
 * every time, out of files written by devices that never coordinated with each other.
 *
 * <p>Plain JUnit. The class touches no SMB, no Android and no clock; liveness arrives as an
 * argument precisely so it can be stated rather than waited for.
 */
public class SmbDownloadStateTest {

    private static final String ME = "client-me";
    private static final String OTHER = "client-other";

    private static Task task(long gid, TaskState state, long claimedAt) {
        return new Task(gid, "tok", "title " + gid, state, 0, 10, claimedAt, null);
    }

    private static Published published(String clientId, boolean alive, Task... tasks) {
        return new Published(
                new ClientState(clientId, clientId + "-name", Arrays.asList(tasks)), alive);
    }

    private static OwnedTask find(List<OwnedTask> merged, long gid) {
        for (OwnedTask o : merged) {
            if (o.task.gid == gid) {
                return o;
            }
        }
        return null;
    }

    // --- merge: one entry per gallery ------------------------------------------------------------

    @Test
    public void merge_combinesDistinctGalleriesFromEveryClient() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published(ME, true, task(1, TaskState.QUEUED, 100)),
                published(OTHER, true, task(2, TaskState.QUEUED, 100))));

        assertEquals(2, merged.size());
        assertEquals(ME, find(merged, 1).clientId);
        assertEquals(OTHER, find(merged, 2).clientId);
    }

    /**
     * The state a takeover leaves behind: the claimer wrote it into its own file and could not
     * touch the original's, so both name the gallery until the original next comes online.
     */
    @Test
    public void merge_liveClaimBeatsDeadClaim() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published(OTHER, false, task(7, TaskState.ACTIVE, 500)),
                published(ME, true, task(7, TaskState.QUEUED, 900))));

        assertEquals(1, merged.size());
        assertEquals(ME, find(merged, 7).clientId);
    }

    /**
     * The rule that makes takeover safe at all. A device that has gone away cannot be corrected,
     * and its clock may be wrong in either direction — so a stale claim must lose <em>whatever</em>
     * its timestamp says, or one dead device could hold a gallery hostage forever.
     */
    @Test
    public void merge_liveClaimBeatsDeadClaimEvenWhenTheDeadOneIsNewer() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published(OTHER, false, task(7, TaskState.ACTIVE, 9_000_000)),
                published(ME, true, task(7, TaskState.QUEUED, 1))));

        assertEquals(ME, find(merged, 7).clientId);
    }

    /** Only once liveness cannot separate them does the timestamp decide. */
    @Test
    public void merge_betweenTwoLiveClaimsTheLaterOneWins() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published(OTHER, true, task(7, TaskState.ACTIVE, 500)),
                published(ME, true, task(7, TaskState.QUEUED, 900))));

        assertEquals(ME, find(merged, 7).clientId);
    }

    @Test
    public void merge_betweenTwoDeadClaimsTheLaterOneStillWins() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published(ME, false, task(7, TaskState.QUEUED, 900)),
                published(OTHER, false, task(7, TaskState.ACTIVE, 500))));

        assertEquals(ME, find(merged, 7).clientId);
        assertFalse(find(merged, 7).ownerAlive);
    }

    /** A file from a newer build is ignored rather than half-understood. */
    @Test
    public void merge_skipsFilesWithAnUnknownSchema() {
        ClientState future = new ClientState(
                SmbDownloadState.SCHEMA_VERSION + 1, OTHER, "future",
                Collections.singletonList(task(7, TaskState.ACTIVE, 900)));

        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                new Published(future, true),
                published(ME, true, task(7, TaskState.QUEUED, 100))));

        assertEquals(1, merged.size());
        assertEquals("the newer file should not have won", ME, find(merged, 7).clientId);
    }

    @Test
    public void merge_ofNothingIsEmpty() {
        assertTrue(SmbDownloadState.merge(new ArrayList<Published>()).isEmpty());
    }

    /** Running work first, then waiting, then held; most recently claimed first within each. */
    @Test
    public void merge_ordersByStateThenRecency() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published(ME, true,
                        task(1, TaskState.PAUSED, 100),
                        task(2, TaskState.QUEUED, 100),
                        task(3, TaskState.ACTIVE, 100),
                        task(4, TaskState.QUEUED, 900))));

        long[] order = new long[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            order[i] = merged.get(i).task.gid;
        }
        assertEquals("[3 active, 4 then 2 queued newest-first, 1 paused]",
                Arrays.toString(new long[]{3, 4, 2, 1}), Arrays.toString(order));
    }

    // --- who may act ------------------------------------------------------------------------------

    @Test
    public void actionable_ownTasksAlways() {
        OwnedTask mine = find(SmbDownloadState.merge(Collections.singletonList(
                published(ME, true, task(1, TaskState.ACTIVE, 100)))), 1);
        assertTrue(mine.isActionableBy(ME));
    }

    /** Another device's running download is somebody else's business. */
    @Test
    public void actionable_notAnotherLiveClientsTasks() {
        OwnedTask theirs = find(SmbDownloadState.merge(Collections.singletonList(
                published(OTHER, true, task(1, TaskState.ACTIVE, 100)))), 1);
        assertFalse(theirs.isActionableBy(ME));
    }

    /** Orphans are the deliberate exception — otherwise they could never be recovered. */
    @Test
    public void actionable_anOrphanIsOpenToAnyone() {
        OwnedTask orphan = find(SmbDownloadState.merge(Collections.singletonList(
                published(OTHER, false, task(1, TaskState.ACTIVE, 100)))), 1);
        assertTrue(orphan.isActionableBy(ME));
    }

    // --- duplicate-download prevention -----------------------------------------------------------

    @Test
    public void claimed_byAnotherLiveClientBlocksEnqueue() {
        List<OwnedTask> merged = SmbDownloadState.merge(Collections.singletonList(
                published(OTHER, true, task(5, TaskState.ACTIVE, 100))));
        assertTrue(SmbDownloadState.isClaimedByAnotherLiveClient(merged, 5, ME));
    }

    /** Its own entries are the downloader's business, not this check's. */
    @Test
    public void claimed_bySelfDoesNotBlock() {
        List<OwnedTask> merged = SmbDownloadState.merge(Collections.singletonList(
                published(ME, true, task(5, TaskState.ACTIVE, 100))));
        assertFalse(SmbDownloadState.isClaimedByAnotherLiveClient(merged, 5, ME));
    }

    /** An orphan must not block, or a dead device would make the gallery undownloadable. */
    @Test
    public void claimed_byADeadClientDoesNotBlock() {
        List<OwnedTask> merged = SmbDownloadState.merge(Collections.singletonList(
                published(OTHER, false, task(5, TaskState.ACTIVE, 100))));
        assertFalse(SmbDownloadState.isClaimedByAnotherLiveClient(merged, 5, ME));
    }

    @Test
    public void claimed_unknownGalleryDoesNotBlock() {
        assertFalse(SmbDownloadState.isClaimedByAnotherLiveClient(
                new ArrayList<OwnedTask>(), 5, ME));
    }

    // --- self-cleaning after a takeover ----------------------------------------------------------

    /**
     * How the duplicate a takeover creates goes away. Nobody writes to another device's file, so
     * the device that lost the task has to notice on its next read and drop the entry itself.
     */
    @Test
    public void selfClean_dropsWhatALiveClientHasSinceClaimed() {
        ClientState self = new ClientState(ME, "me", Arrays.asList(
                task(1, TaskState.QUEUED, 100),
                task(2, TaskState.QUEUED, 100)));
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                new Published(self, true),
                published(OTHER, true, task(2, TaskState.ACTIVE, 900))));

        List<Task> kept = SmbDownloadState.withoutTakenOver(self, merged);

        assertEquals(1, kept.size());
        assertEquals("gallery 2 was taken over and should be gone", 1, kept.get(0).gid);
    }

    /** A claim older than ours is not a takeover of ours; we keep the task. */
    @Test
    public void selfClean_keepsWhatAnotherClientClaimedEarlier() {
        ClientState self = new ClientState(ME, "me",
                Collections.singletonList(task(2, TaskState.QUEUED, 900)));
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                new Published(self, true),
                published(OTHER, true, task(2, TaskState.ACTIVE, 100))));

        assertEquals(1, SmbDownloadState.withoutTakenOver(self, merged).size());
    }

    /** And a dead client's claim takes nothing from us however new it looks. */
    @Test
    public void selfClean_keepsWhatOnlyADeadClientClaims() {
        ClientState self = new ClientState(ME, "me",
                Collections.singletonList(task(2, TaskState.QUEUED, 100)));
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                new Published(self, true),
                published(OTHER, false, task(2, TaskState.ACTIVE, 900))));

        assertEquals(1, SmbDownloadState.withoutTakenOver(self, merged).size());
    }

    // --- json --------------------------------------------------------------------------------------

    @Test
    public void json_roundTripsEveryField() {
        ClientState original = new ClientState(ME, "Living room tablet", Arrays.asList(
                new Task(11, "tok11", "first", TaskState.ACTIVE, 3, 20, 1234L, null),
                new Task(22, null, null, TaskState.PAUSED, 0, 0, 5678L, OTHER)));

        ClientState back = SmbDownloadState.parse(SmbDownloadState.serialize(original));

        assertNotNull(back);
        assertEquals(SmbDownloadState.SCHEMA_VERSION, back.schemaVersion);
        assertEquals(ME, back.clientId);
        assertEquals("Living room tablet", back.deviceName);
        assertEquals(2, back.tasks.size());

        Task a = back.tasks.get(0);
        assertEquals(11, a.gid);
        assertEquals("tok11", a.token);
        assertEquals("first", a.title);
        assertEquals(TaskState.ACTIVE, a.state);
        assertEquals(3, a.finished);
        assertEquals(20, a.total);
        assertEquals(1234L, a.claimedAt);
        assertNull(a.takenOverFrom);

        Task b = back.tasks.get(1);
        assertEquals(22, b.gid);
        assertNull(b.token);
        assertNull(b.title);
        assertEquals(TaskState.PAUSED, b.state);
        assertEquals(OTHER, b.takenOverFrom);
    }

    /** Readable on the NAS is the reason this is JSON and not something denser. */
    @Test
    public void json_isIndented() {
        String out = SmbDownloadState.serialize(new ClientState(ME, "me",
                Collections.singletonList(task(1, TaskState.QUEUED, 100))));
        assertTrue("expected pretty-printed output, got: " + out, out.contains("\n"));
    }

    /**
     * A half-written or corrupt file must cost only its own device's visibility, never the whole
     * list — every one of these is something a reader can genuinely meet on a live share.
     */
    @Test
    public void json_unreadableInputYieldsNullRatherThanThrowing() {
        assertNull(SmbDownloadState.parse(null));
        assertNull(SmbDownloadState.parse(""));
        assertNull(SmbDownloadState.parse("not json at all"));
        assertNull(SmbDownloadState.parse("{\"tasks\":[]}"));            // no clientId
        assertNull(SmbDownloadState.parse("{\"clientId\":\"\"}"));        // blank clientId
        assertNull(SmbDownloadState.parse("{\"clientId\":\"x\",\"tas")); // truncated mid-write
    }

    @Test
    public void json_toleratesMissingOptionalFields() {
        ClientState s = SmbDownloadState.parse(
                "{\"clientId\":\"c1\",\"tasks\":[{\"gid\":9}]}");

        assertNotNull(s);
        assertEquals("deviceName should fall back to the id", "c1", s.deviceName);
        assertEquals(1, s.tasks.size());
        assertEquals(9, s.tasks.get(0).gid);
        assertEquals("an unstated state means it is waiting", TaskState.QUEUED, s.tasks.get(0).state);
        assertEquals(0, s.tasks.get(0).total);
    }

    /** A task entry with no gid identifies nothing, so it is dropped rather than defaulted to 0. */
    @Test
    public void json_dropsTaskEntriesWithoutAGid() {
        ClientState s = SmbDownloadState.parse(
                "{\"clientId\":\"c1\",\"tasks\":[{\"title\":\"x\"},{\"gid\":9}]}");

        assertNotNull(s);
        assertEquals(1, s.tasks.size());
        assertEquals(9, s.tasks.get(0).gid);
    }

    /** A file from a newer build parses, but says so — merge is what refuses to use it. */
    @Test
    public void json_keepsAnUnknownSchemaVersionVisible() {
        ClientState s = SmbDownloadState.parse(
                "{\"schemaVersion\":99,\"clientId\":\"c1\",\"tasks\":[]}");

        assertNotNull(s);
        assertEquals(99, s.schemaVersion);
        assertFalse(s.isReadable());
    }
}
