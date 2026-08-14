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

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The rules by which several devices' published download state becomes one list (#59). */
public class SmbDownloadStateTest {

    private static final String ME = "client-me";
    private static final String OTHER = "client-other";

    private static Task task(long gid, long claimedAt) {
        return new Task(gid, "tok", "title " + gid, 0, 10, claimedAt, null);
    }

    /** @param alive whether that device's file was fresh when the directory was read */
    private static Published published(String clientId, boolean alive, Task... tasks) {
        return published(clientId, clientId + "-name", alive, tasks);
    }

    private static Published published(String clientId, String deviceName, boolean alive,
                                       Task... tasks) {
        return new Published(
                new ClientState(clientId, deviceName, Arrays.asList(tasks)), alive, 0L);
    }

    private static OwnedTask find(List<OwnedTask> merged, long gid) {
        for (OwnedTask o : merged) {
            if (o.task.gid == gid) {
                return o;
            }
        }
        return null;
    }

    private static long[] gidsOf(List<OwnedTask> merged) {
        long[] out = new long[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            out[i] = merged.get(i).task.gid;
        }
        return out;
    }

    // --- merge: at most one entry per gallery ----------------------------------------------------

    @Test
    public void merge_combinesDistinctGalleriesFromEveryClient() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published(ME, true, task(1, 100)),
                published(OTHER, true, task(2, 100))));

        assertEquals(2, merged.size());
        assertEquals(ME, find(merged, 1).clientId);
        assertEquals(OTHER, find(merged, 2).clientId);
    }

    /** The state a takeover leaves behind, and the rule that makes takeover safe at all. */
    @Test
    public void merge_aLiveClaimBeatsADeadOneWhateverTheTimestampsSay() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published(OTHER, false, task(7, 9_000_000L)),
                published(ME, true, task(7, 1L))));

        assertEquals(1, merged.size());
        assertEquals(ME, find(merged, 7).clientId);
    }

    /** Only once liveness cannot separate two claims does the timestamp decide. */
    @Test
    public void merge_betweenTwoLiveClaimsTheLaterOneWins() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published(OTHER, true, task(7, 500)),
                published(ME, true, task(7, 900))));

        assertEquals(ME, find(merged, 7).clientId);
    }

    /** A file written by a newer build is ignored rather than half-understood. */
    @Test
    public void merge_skipsFilesWithAnUnknownSchema() {
        ClientState future = new ClientState(
                SmbDownloadState.SCHEMA_VERSION + 1, OTHER, "future",
                Collections.singletonList(task(7, 900)));

        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                new Published(future, true, 0L),
                published(ME, true, task(7, 100))));

        assertEquals(1, merged.size());
        assertEquals("the newer file should not have won", ME, find(merged, 7).clientId);
    }

    @Test
    public void merge_ofNothingIsEmpty() {
        assertTrue(SmbDownloadState.merge(Collections.<Published>emptyList()).isEmpty());
    }

    // --- display order ---------------------------------------------------------------------------

    /** One gallery per device first — the one that device is actually downloading — then everything queued behind, oldest claim first. */
    @Test
    public void order_whatEachDeviceIsOnComesFirst() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published("a", "Alpha", true, task(1, 1000), task(2, 3000)),
                published("b", "Bravo", true, task(3, 2000), task(4, 4000))));

        assertEquals("[Alpha head, Bravo head, then the rest oldest-claim first]",
                Arrays.toString(new long[]{1, 3, 2, 4}),
                Arrays.toString(gidsOf(merged)));
    }

    /** Heads sort by device name, so a heartbeat landing never reshuffles rows under a reader. */
    @Test
    public void order_headsSortByDeviceNameNotByClaimTime() {
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                published("z", "Alpha", true, task(1, 5000)),
                published("m", "Bravo", true, task(2, 1000)),
                published("a", "Charlie", true, task(3, 9000))));

        assertEquals("[by name; oldest-first would be 2,1,3 and newest-first 3,1,2]",
                Arrays.toString(new long[]{1, 2, 3}), Arrays.toString(gidsOf(merged)));
    }

    // --- letting go of what is no longer ours ----------------------------------------------------

    /**
     * How the duplicate a takeover creates goes away on the losing side. A device that was offline
     * cannot be told it lost a task — it has to notice on its next read and drop the entry itself.
     */
    @Test
    public void selfClean_dropsWhatALiveClientHasSinceClaimed() {
        ClientState self = new ClientState(ME, "me", Arrays.asList(task(1, 100), task(2, 100)));
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                new Published(self, true, 0L),
                published(OTHER, true, task(2, 900))));

        List<Task> kept = SmbDownloadState.withoutTakenOver(self, merged);

        assertEquals(1, kept.size());
        assertEquals("gallery 2 was taken over and should be gone", 1, kept.get(0).gid);
    }

    /** A claim older than ours is not a takeover of ours. */
    @Test
    public void selfClean_keepsWhatAnotherClientClaimedEarlier() {
        ClientState self = new ClientState(ME, "me", Collections.singletonList(task(2, 900)));
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                new Published(self, true, 0L),
                published(OTHER, true, task(2, 100))));

        assertEquals(1, SmbDownloadState.withoutTakenOver(self, merged).size());
    }

    /** And a dead client's claim takes nothing from us, however new it looks. */
    @Test
    public void selfClean_keepsWhatOnlyADeadClientClaims() {
        ClientState self = new ClientState(ME, "me", Collections.singletonList(task(2, 100)));
        List<OwnedTask> merged = SmbDownloadState.merge(Arrays.asList(
                new Published(self, true, 0L),
                published(OTHER, false, task(2, 900))));

        assertEquals(1, SmbDownloadState.withoutTakenOver(self, merged).size());
    }

    // --- not downloading the same gallery twice ---------------------------------------------------

    @Test
    public void claimed_byAnotherLiveClientBlocksEnqueue() {
        List<OwnedTask> merged = SmbDownloadState.merge(Collections.singletonList(
                published(OTHER, true, task(5, 100))));

        assertTrue(SmbDownloadState.isClaimedByAnotherLiveClient(merged, 5, ME));
    }

    /** Our own claim is not something to block ourselves on. */
    @Test
    public void claimed_ownTasksDoNotBlock() {
        List<OwnedTask> merged = SmbDownloadState.merge(Collections.singletonList(
                published(ME, true, task(5, 100))));

        assertFalse(SmbDownloadState.isClaimedByAnotherLiveClient(merged, 5, ME));
    }

    /**
     * Nor does an abandoned one — that is the whole point of being able to take it over, and the
     * negative half of this rule is the one worth pinning: without it an orphan is unreachable.
     */
    @Test
    public void claimed_anOrphanDoesNotBlock() {
        List<OwnedTask> merged = SmbDownloadState.merge(Collections.singletonList(
                published(OTHER, false, task(5, 100))));

        assertFalse(SmbDownloadState.isClaimedByAnotherLiveClient(merged, 5, ME));
    }

    @Test
    public void claimed_saysNothingAboutAGalleryNobodyHas() {
        assertFalse(SmbDownloadState.isClaimedByAnotherLiveClient(
                SmbDownloadState.merge(Collections.<Published>emptyList()), 5, ME));
    }

    // --- who may do what --------------------------------------------------------------------------

    /**
     * Another device's download is not ours to pause or delete: it would carry on regardless, and
     * removing it from the list would mean editing a file only its owner may write.
     */
    @Test
    public void actionable_ownTasksOnly() {
        OwnedTask mine = find(SmbDownloadState.merge(Collections.singletonList(
                published(ME, true, task(1, 100)))), 1);
        OwnedTask theirs = find(SmbDownloadState.merge(Collections.singletonList(
                published(OTHER, true, task(2, 100)))), 2);

        assertTrue(mine.isActionableBy(ME));
        assertFalse(theirs.isActionableBy(ME));
    }

    /** An orphan is adopted, not operated on — and only an orphan, and never one's own. */
    @Test
    public void takeOver_onlySomebodyElsesAbandonedWork() {
        OwnedTask orphan = find(SmbDownloadState.merge(Collections.singletonList(
                published(OTHER, false, task(1, 100)))), 1);
        OwnedTask live = find(SmbDownloadState.merge(Collections.singletonList(
                published(OTHER, true, task(2, 100)))), 2);
        OwnedTask ownAndQuiet = find(SmbDownloadState.merge(Collections.singletonList(
                published(ME, false, task(3, 100)))), 3);

        assertTrue(orphan.isTakeOverableBy(ME));
        assertFalse(orphan.isActionableBy(ME));
        assertFalse(live.isTakeOverableBy(ME));
        assertFalse(ownAndQuiet.isTakeOverableBy(ME));
    }

    // --- the file on the share ---------------------------------------------------------------------

    /** What one device writes, another must read back unchanged; there is no other channel. */
    @Test
    public void json_roundTripsEveryFieldThatMatters() {
        ClientState written = new ClientState(ME, "Study phone", Collections.singletonList(
                new Task(7, "tok7", "a title", 12, 36, 1700L, OTHER)));

        ClientState read = SmbDownloadState.parse(SmbDownloadState.serialize(written));

        assertNotNull(read);
        assertEquals(ME, read.clientId);
        assertEquals("Study phone", read.deviceName);
        assertEquals(1, read.tasks.size());
        Task t = read.tasks.get(0);
        assertEquals(7, t.gid);
        assertEquals("tok7", t.token);
        assertEquals("a title", t.title);
        assertEquals(12, t.finished);
        assertEquals(36, t.total);
        assertEquals(1700L, t.claimedAt);
        assertEquals(OTHER, t.takenOverFrom);
    }

    /** One corrupt or half-written file must not take the whole list down with it. */
    @Test
    public void parse_returnsNullRatherThanThrowing() {
        assertNull(SmbDownloadState.parse(null));
        assertNull(SmbDownloadState.parse(""));
        assertNull(SmbDownloadState.parse("not json at all"));
        assertNull(SmbDownloadState.parse("{\"tasks\":[]}"));   // no clientId: not usable
    }

    // ------------------------------------------------------------- planReconcile / assessTakeOver

    /**
     * The reconcile decision, pure (#98): held-but-outclaimed yields, published-but-lost
     * restores, and nothing else. The board applies this; nothing else may decide.
     */
    @Test
    public void plan_yieldsWhatALiveRivalClaimedMoreRecently() {
        ClientState held = new ClientState(ME, "me", Arrays.asList(task(42, 1_000)));
        SmbDownloadState.ReconcilePlan plan = SmbDownloadState.planReconcile(ME, held,
                Arrays.asList(published(ME, true, task(42, 1_000)),
                        published(OTHER, true, task(42, 2_000))),
                gid -> false);
        assertEquals(Arrays.asList(42L), plan.yields);
        assertTrue(plan.restores.isEmpty());
    }

    @Test
    public void plan_restoresWhatWasPublishedButIsNoLongerHeld() {
        ClientState held = new ClientState(ME, "me", Arrays.asList());
        SmbDownloadState.ReconcilePlan plan = SmbDownloadState.planReconcile(ME, held,
                Arrays.asList(published(ME, true, task(7, 1_000))),
                gid -> false);
        assertTrue(plan.yields.isEmpty());
        assertEquals(1, plan.restores.size());
        assertEquals(7L, plan.restores.get(0).gid);
        assertFalse("something to restore: the restore itself will publish", plan.shouldPublish);
    }

    /** The retired must stay retired; the plan says publish instead, to clear the stale claim. */
    @Test
    public void plan_leavesTheRetiredDownAndPublishesInstead() {
        ClientState held = new ClientState(ME, "me", Arrays.asList());
        SmbDownloadState.ReconcilePlan plan = SmbDownloadState.planReconcile(ME, held,
                Arrays.asList(published(ME, true, task(7, 1_000))),
                gid -> gid == 7L);
        assertTrue(plan.restores.isEmpty());
        assertTrue(plan.shouldPublish);
    }

    /** Never published: nothing to restore and no file to correct, so no publish either. */
    @Test
    public void plan_doesNothingWhenThisDeviceNeverPublished() {
        ClientState held = new ClientState(ME, "me", Arrays.asList());
        SmbDownloadState.ReconcilePlan plan = SmbDownloadState.planReconcile(ME, held,
                Arrays.asList(published(OTHER, true, task(9, 1_000))),
                gid -> false);
        assertTrue(plan.yields.isEmpty());
        assertTrue(plan.restores.isEmpty());
        assertFalse(plan.shouldPublish);
    }

    /** The three answers a fresh look can give a takeover, in one place. */
    @Test
    public void assess_ordersTheThreeTakeoverAnswers() {
        assertEquals(SmbDownloadState.TakeOverAssessment.ALREADY_OURS,
                SmbDownloadState.assessTakeOver(
                        SmbDownloadState.merge(Arrays.asList(published(ME, true, task(1, 1_000)))),
                        1, ME));
        assertEquals(SmbDownloadState.TakeOverAssessment.OWNER_ALIVE,
                SmbDownloadState.assessTakeOver(
                        SmbDownloadState.merge(Arrays.asList(published(OTHER, true, task(1, 1_000)))),
                        1, ME));
        assertEquals(SmbDownloadState.TakeOverAssessment.ORPHAN,
                SmbDownloadState.assessTakeOver(
                        SmbDownloadState.merge(Arrays.asList(published(OTHER, false, task(1, 1_000)))),
                        1, ME));
    }
}
