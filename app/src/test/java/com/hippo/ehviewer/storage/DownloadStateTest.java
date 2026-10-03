package com.hippo.ehviewer.storage;

import com.hippo.ehviewer.storage.DownloadState.ClientState;
import com.hippo.ehviewer.storage.DownloadState.OwnedTask;
import com.hippo.ehviewer.storage.DownloadState.Published;
import com.hippo.ehviewer.storage.DownloadState.Task;
import com.hippo.ehviewer.storage.DownloadState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;


import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The rules by which several devices' published download state becomes one list (#59). */
public class DownloadStateTest {

    private static final String ME = "client-me";
    private static final String OTHER = "client-other";

    private static Task task(long gid, long claimedAt) {
        return new Task(gid, "tok", "title " + gid, 0, 10, claimedAt, null);
    }

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

    @Test
    public void merge_combinesDistinctGalleriesFromEveryClient() {
        List<OwnedTask> merged = DownloadState.merge(Arrays.asList(
                published(ME, true, task(1, 100)),
                published(OTHER, true, task(2, 100))));

        assertEquals(2, merged.size());
        assertEquals(ME, find(merged, 1).clientId);
        assertEquals(OTHER, find(merged, 2).clientId);
    }

    @Test
    public void merge_aLiveClaimBeatsADeadOneWhateverTheTimestampsSay() {
        List<OwnedTask> merged = DownloadState.merge(Arrays.asList(
                published(OTHER, false, task(7, 9_000_000L)),
                published(ME, true, task(7, 1L))));

        assertEquals(1, merged.size());
        assertEquals(ME, find(merged, 7).clientId);
    }

    @Test
    public void merge_betweenTwoLiveClaimsTheLaterOneWins() {
        List<OwnedTask> merged = DownloadState.merge(Arrays.asList(
                published(OTHER, true, task(7, 500)),
                published(ME, true, task(7, 900))));

        assertEquals(ME, find(merged, 7).clientId);
    }

    @Test
    public void merge_skipsFilesWithAnUnknownSchema() {
        ClientState future = new ClientState(
                DownloadState.SCHEMA_VERSION + 1, OTHER, "future",
                Collections.singletonList(task(7, 900)));

        List<OwnedTask> merged = DownloadState.merge(Arrays.asList(
                new Published(future, true, 0L),
                published(ME, true, task(7, 100))));

        assertEquals(1, merged.size());
        assertEquals("the newer file should not have won", ME, find(merged, 7).clientId);
    }

    @Test
    public void merge_ofNothingIsEmpty() {
        assertTrue(DownloadState.merge(Collections.<Published>emptyList()).isEmpty());
    }

    @Test
    public void order_whatEachDeviceIsOnComesFirst() {
        List<OwnedTask> merged = DownloadState.merge(Arrays.asList(
                published("a", "Alpha", true, task(1, 1000), task(2, 3000)),
                published("b", "Bravo", true, task(3, 2000), task(4, 4000))));

        assertEquals("[Alpha head, Bravo head, then the rest oldest-claim first]",
                Arrays.toString(new long[]{1, 3, 2, 4}),
                Arrays.toString(gidsOf(merged)));
    }

    @Test
    public void order_headsSortByDeviceNameNotByClaimTime() {
        List<OwnedTask> merged = DownloadState.merge(Arrays.asList(
                published("z", "Alpha", true, task(1, 5000)),
                published("m", "Bravo", true, task(2, 1000)),
                published("a", "Charlie", true, task(3, 9000))));

        assertEquals("[by name; oldest-first would be 2,1,3 and newest-first 3,1,2]",
                Arrays.toString(new long[]{1, 2, 3}), Arrays.toString(gidsOf(merged)));
    }

    @Test
    public void selfClean_dropsWhatALiveClientHasSinceClaimed() {
        ClientState self = new ClientState(ME, "me", Arrays.asList(task(1, 100), task(2, 100)));
        List<OwnedTask> merged = DownloadState.merge(Arrays.asList(
                new Published(self, true, 0L),
                published(OTHER, true, task(2, 900))));

        List<Task> kept = DownloadState.withoutTakenOver(self, merged);

        assertEquals(1, kept.size());
        assertEquals("gallery 2 was taken over and should be gone", 1, kept.get(0).gid);
    }

    @Test
    public void selfClean_keepsWhatAnotherClientClaimedEarlier() {
        ClientState self = new ClientState(ME, "me", Collections.singletonList(task(2, 900)));
        List<OwnedTask> merged = DownloadState.merge(Arrays.asList(
                new Published(self, true, 0L),
                published(OTHER, true, task(2, 100))));

        assertEquals(1, DownloadState.withoutTakenOver(self, merged).size());
    }

    @Test
    public void selfClean_keepsWhatOnlyADeadClientClaims() {
        ClientState self = new ClientState(ME, "me", Collections.singletonList(task(2, 100)));
        List<OwnedTask> merged = DownloadState.merge(Arrays.asList(
                new Published(self, true, 0L),
                published(OTHER, false, task(2, 900))));

        assertEquals(1, DownloadState.withoutTakenOver(self, merged).size());
    }

    @Test
    public void claimed_byAnotherLiveClientBlocksEnqueue() {
        List<OwnedTask> merged = DownloadState.merge(Collections.singletonList(
                published(OTHER, true, task(5, 100))));

        assertTrue(DownloadState.isClaimedByAnotherLiveClient(merged, 5, ME));
    }

    @Test
    public void claimed_ownTasksDoNotBlock() {
        List<OwnedTask> merged = DownloadState.merge(Collections.singletonList(
                published(ME, true, task(5, 100))));

        assertFalse(DownloadState.isClaimedByAnotherLiveClient(merged, 5, ME));
    }

    @Test
    public void claimed_anOrphanDoesNotBlock() {
        List<OwnedTask> merged = DownloadState.merge(Collections.singletonList(
                published(OTHER, false, task(5, 100))));

        assertFalse(DownloadState.isClaimedByAnotherLiveClient(merged, 5, ME));
    }

    @Test
    public void claimed_saysNothingAboutAGalleryNobodyHas() {
        assertFalse(DownloadState.isClaimedByAnotherLiveClient(
                DownloadState.merge(Collections.<Published>emptyList()), 5, ME));
    }

    @Test
    public void actionable_ownTasksOnly() {
        OwnedTask mine = find(DownloadState.merge(Collections.singletonList(
                published(ME, true, task(1, 100)))), 1);
        OwnedTask theirs = find(DownloadState.merge(Collections.singletonList(
                published(OTHER, true, task(2, 100)))), 2);

        assertTrue(mine.isActionableBy(ME));
        assertFalse(theirs.isActionableBy(ME));
    }

    @Test
    public void takeOver_onlySomebodyElsesAbandonedWork() {
        OwnedTask orphan = find(DownloadState.merge(Collections.singletonList(
                published(OTHER, false, task(1, 100)))), 1);
        OwnedTask live = find(DownloadState.merge(Collections.singletonList(
                published(OTHER, true, task(2, 100)))), 2);
        OwnedTask ownAndQuiet = find(DownloadState.merge(Collections.singletonList(
                published(ME, false, task(3, 100)))), 3);

        assertTrue(orphan.isTakeOverableBy(ME));
        assertFalse(orphan.isActionableBy(ME));
        assertFalse(live.isTakeOverableBy(ME));
        assertFalse(ownAndQuiet.isTakeOverableBy(ME));
    }

    @Test
    public void json_roundTripsEveryFieldThatMatters() {
        ClientState written = new ClientState(ME, "Study phone", Collections.singletonList(
                new Task(7, "tok7", "a title", 12, 36, 1700L, OTHER)));

        ClientState read = DownloadState.parse(DownloadState.serialize(written));

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

    @Test
    public void parse_returnsNullRatherThanThrowing() {
        assertNull(DownloadState.parse(null));
        assertNull(DownloadState.parse(""));
        assertNull(DownloadState.parse("not json at all"));
        assertNull(DownloadState.parse("{\"tasks\":[]}"));   // no clientId: not usable
    }

    @Test
    public void plan_yieldsWhatALiveRivalClaimedMoreRecently() {
        ClientState held = new ClientState(ME, "me", Arrays.asList(task(42, 1_000)));
        DownloadState.ReconcilePlan plan = DownloadState.planReconcile(ME, held,
                Arrays.asList(published(ME, true, task(42, 1_000)),
                        published(OTHER, true, task(42, 2_000))),
                gid -> false);
        assertEquals(Arrays.asList(42L), plan.yields);
        assertTrue(plan.restores.isEmpty());
    }

    @Test
    public void plan_restoresWhatWasPublishedButIsNoLongerHeld() {
        ClientState held = new ClientState(ME, "me", Arrays.asList());
        DownloadState.ReconcilePlan plan = DownloadState.planReconcile(ME, held,
                Arrays.asList(published(ME, true, task(7, 1_000))),
                gid -> false);
        assertTrue(plan.yields.isEmpty());
        assertEquals(1, plan.restores.size());
        assertEquals(7L, plan.restores.get(0).gid);
        assertFalse("something to restore: the restore itself will publish", plan.shouldPublish);
    }

    @Test
    public void plan_leavesTheRetiredDownAndPublishesInstead() {
        ClientState held = new ClientState(ME, "me", Arrays.asList());
        DownloadState.ReconcilePlan plan = DownloadState.planReconcile(ME, held,
                Arrays.asList(published(ME, true, task(7, 1_000))),
                gid -> gid == 7L);
        assertTrue(plan.restores.isEmpty());
        assertTrue(plan.shouldPublish);
    }

    @Test
    public void plan_doesNothingWhenThisDeviceNeverPublished() {
        ClientState held = new ClientState(ME, "me", Arrays.asList());
        DownloadState.ReconcilePlan plan = DownloadState.planReconcile(ME, held,
                Arrays.asList(published(OTHER, true, task(9, 1_000))),
                gid -> false);
        assertTrue(plan.yields.isEmpty());
        assertTrue(plan.restores.isEmpty());
        assertFalse(plan.shouldPublish);
    }

    @Test
    public void assess_ordersTheThreeTakeoverAnswers() {
        assertEquals(DownloadState.TakeOverAssessment.ALREADY_OURS,
                DownloadState.assessTakeOver(
                        DownloadState.merge(Arrays.asList(published(ME, true, task(1, 1_000)))),
                        1, ME));
        assertEquals(DownloadState.TakeOverAssessment.OWNER_ALIVE,
                DownloadState.assessTakeOver(
                        DownloadState.merge(Arrays.asList(published(OTHER, true, task(1, 1_000)))),
                        1, ME));
        assertEquals(DownloadState.TakeOverAssessment.ORPHAN,
                DownloadState.assessTakeOver(
                        DownloadState.merge(Arrays.asList(published(OTHER, false, task(1, 1_000)))),
                        1, ME));
    }
}
