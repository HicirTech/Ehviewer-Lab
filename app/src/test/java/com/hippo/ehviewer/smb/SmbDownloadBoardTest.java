/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import com.hippo.ehviewer.storage.DownloadState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The share side of the download queue, tested without a device side (#98): a fake SmbDownloadBoard.Device records what the board asks of it, and a shad */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class,
        shadows = {SmbDownloadBoardTest.ShadowStore.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class SmbDownloadBoardTest {

    static final List<DownloadState.Published> onShare = new ArrayList<>();
    static final List<String> storeWrites = new ArrayList<>();

    @Implements(SmbDownloadStateStore.class)
    public static class ShadowStore {
        @Implementation
        protected static List<DownloadState.Published> readAll() {
            return new ArrayList<>(onShare);
        }

        @Implementation
        protected static boolean writeSelf(DownloadState.ClientState state) {
            storeWrites.add("writeSelf:" + state.clientId);
            return true;
        }

        @Implementation
        protected static boolean removeTask(String ownerClientId, long gid) {
            storeWrites.add("removeTask:" + ownerClientId + ":" + gid);
            return true;
        }
    }

    /** Records every question; answers with whatever the test seeded. */
    static final class FakeDevice implements SmbDownloadBoard.Device {
        final List<String> calls = new CopyOnWriteArrayList<>();
        List<DownloadState.Task> held = new ArrayList<>();
        List<Long> retired = new ArrayList<>();

        @Override
        @NonNull
        public DownloadState.ClientState snapshot() {
            return new DownloadState.ClientState(
                    Settings.getSmbClientId(), "test-device", new ArrayList<>(held));
        }

        @Override
        public boolean hasWork() {
            return !held.isEmpty();
        }

        @Override
        public boolean isRetired(long gid) {
            return retired.contains(gid);
        }

        @Override
        public void yieldTask(long gid) {
            calls.add("yield:" + gid);
        }

        @Override
        public void restore(@NonNull List<DownloadState.Task> tasks) {
            StringBuilder gids = new StringBuilder();
            for (DownloadState.Task t : tasks) {
                gids.append(t.gid).append(',');
            }
            calls.add("restore:" + gids);
        }

        @Override
        public void stampAdoption(@NonNull SmbTaskInfo task) {
            calls.add("stamp:" + task.gid);
        }

        @Override
        public void enqueueAdopted(@NonNull android.content.Context context,
                                   @NonNull GalleryInfo info) {
            calls.add("enqueue:" + info.gid);
        }

        @Override
        public int localRowState(long gid) {
            return com.hippo.ehviewer.dao.DownloadInfo.STATE_WAIT;
        }
    }

    private FakeDevice device;
    private SmbDownloadBoard board;
    private String selfId;

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(Settings.KEY_SMB_SHARE_NAME, "share");
        Settings.putBoolean(Settings.KEY_SMB_SAVE_ENABLED, true);
        onShare.clear();
        storeWrites.clear();
        device = new FakeDevice();
        board = new SmbDownloadBoard(device);
        selfId = Settings.getSmbClientId();
    }

    private static DownloadState.Task task(long gid, long claimedAt) {
        return new DownloadState.Task(gid, "token" + gid, "Gallery " + gid,
                3, 10, claimedAt, null);
    }

    private static DownloadState.Published published(
            String clientId, boolean alive, DownloadState.Task... tasks) {
        List<DownloadState.Task> list = new ArrayList<>();
        java.util.Collections.addAll(list, tasks);
        return new DownloadState.Published(
                new DownloadState.ClientState(clientId, "device " + clientId, list),
                alive, System.currentTimeMillis());
    }

    /** The board runs on its own publisher thread; wait for the fake to hear from it. */
    private void await(String prefix) {
        long end = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < end) {
            for (String c : device.calls) {
                if (c.startsWith(prefix)) {
                    return;
                }
            }
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        throw new AssertionError("board never called " + prefix + "; calls=" + device.calls);
    }

    /** A task this device holds that a live device claimed more recently is not ours any more: the board tells the device to stand down, and touches nothing */
    @Test
    public void reconcileYieldsWhatWasTakenOverElsewhere() {
        device.held.add(task(42L, 1_000L));
        onShare.add(published(selfId, true, task(42L, 1_000L)));
        onShare.add(published("rival", true, task(42L, 2_000L)));

        board.scheduleReconcile();
        await("yield:42");
        assertTrue("nothing shared may be touched on a yield",
                storeWrites.stream().noneMatch(w -> w.startsWith("removeTask")));
    }

    /** What this device published but no longer holds — a process death — comes back. */
    @Test
    public void reconcileRestoresWhatTheProcessLost() {
        onShare.add(published(selfId, true, task(7L, 1_000L)));

        board.scheduleReconcile();
        await("restore:7,");
    }

    /** A gallery finished this process-lifetime must not be resurrected by a stale read. */
    @Test
    public void reconcileDoesNotResurrectTheRetired() {
        device.retired.add(7L);
        onShare.add(published(selfId, true, task(7L, 1_000L)));

        board.scheduleReconcile();
        long end = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < end
                && storeWrites.stream().noneMatch(w -> w.startsWith("writeSelf"))) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        assertTrue("a retired gid came back: " + device.calls,
                device.calls.stream().noneMatch(c -> c.startsWith("restore")));
        // The board publishes instead, so the share stops advertising the stale claim.
        assertTrue(storeWrites.stream().anyMatch(w -> w.startsWith("writeSelf")));
    }

    /** An owner who turned out to be alive keeps the download; nothing is stamped or enqueued. */
    @Test
    public void takeOverLeavesALiveOwnerAlone() {
        onShare.add(published("rival", true, task(42L, 1_000L)));
        SmbTaskInfo row = sharedRow(42L);

        final SmbDownloadBoard.TakeOverResult[] got = new SmbDownloadBoard.TakeOverResult[1];
        board.takeOver(RuntimeEnvironment.getApplication(), row, result -> got[0] = result);
        awaitPumpedResult(got);
        assertEquals(SmbDownloadBoard.TakeOverResult.OWNER_RETURNED, got[0]);
        assertTrue(device.calls.isEmpty());
    }

    /** A dead owner's task is adopted: stamped, cleared from the owner's file, enqueued here. */
    @Test
    public void takeOverAdoptsAnOrphan() {
        onShare.add(published("rival", false, task(42L, 1_000L)));
        SmbTaskInfo row = sharedRow(42L);

        final SmbDownloadBoard.TakeOverResult[] got = new SmbDownloadBoard.TakeOverResult[1];
        board.takeOver(RuntimeEnvironment.getApplication(), row, result -> got[0] = result);
        awaitPumpedResult(got);
        assertEquals(SmbDownloadBoard.TakeOverResult.TAKEN, got[0]);
        assertEquals(List.of("stamp:42", "enqueue:42"), device.calls);
        assertTrue(storeWrites.contains("removeTask:rival:42"));
    }

    /** Builds the row a user would tap, out of what the shadowed share holds. */
    private SmbTaskInfo sharedRow(long gid) {
        for (SmbTaskInfo t : board.snapshotSharedTasks()) {
            if (t.gid == gid) {
                return t;
            }
        }
        throw new AssertionError("gid " + gid + " not on the shared list");
    }

    /** The result callback is posted to the main looper; run it, then read the answer. */
    private void awaitPumpedResult(SmbDownloadBoard.TakeOverResult[] got) {
        long end = System.currentTimeMillis() + 5_000;
        while (got[0] == null && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        if (got[0] == null) {
            throw new AssertionError("takeover never reported a result");
        }
    }
}
