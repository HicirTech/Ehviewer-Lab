/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.smb.SmbDownloadBoard;
import com.hippo.ehviewer.smb.SmbDownloadState;
import com.hippo.ehviewer.smb.SmbTaskInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The badge feed (#99): marks from claims, delivered only when they changed. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {InventoryBadgesTest.ShadowBoard.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class InventoryBadgesTest {

    static final List<SmbTaskInfo> tasksOnShare = new ArrayList<>();

    @Implements(SmbDownloadBoard.class)
    public static class ShadowBoard {
        @Implementation
        protected List<SmbTaskInfo> snapshotSharedTasks() {
            return new ArrayList<>(tasksOnShare);
        }
    }

    private final List<Map<Long, InventoryBadges.Mark>> heard = new ArrayList<>();
    private InventoryBadges badges;

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(Settings.KEY_SMB_SHARE_NAME, "share");
        Settings.putBoolean(Settings.KEY_SMB_SAVE_ENABLED, true);
        tasksOnShare.clear();
        heard.clear();
        badges = new InventoryBadges(Runnable::run, heard::add);
    }

    /** Bypasses the burst limiter so each test call really reads. */
    private void refreshNow() throws Exception {
        Field f = InventoryBadges.class.getDeclaredField("lastRefreshAt");
        f.setAccessible(true);
        f.setLong(badges, 0L);
        badges.refresh();
        ShadowLooper.idleMainLooper();
    }

    private static SmbTaskInfo task(long gid, int finished, int total) {
        SmbDownloadState.Task t = new SmbDownloadState.Task(gid, "tok", "T" + gid,
                finished, total, 1_000L, null);
        SmbDownloadState.Published p = new SmbDownloadState.Published(
                new SmbDownloadState.ClientState("owner", "owner-device", Arrays.asList(t)),
                true, System.currentTimeMillis());
        SmbDownloadState.OwnedTask owned = SmbDownloadState.merge(Arrays.asList(p)).get(0);
        return SmbTaskInfo.of(owned, "someone-else", null,
                com.hippo.ehviewer.dao.DownloadInfo.STATE_DOWNLOAD);
    }

    /** A claim becomes a mark carrying owner and fraction. */
    @Test
    public void marksCarryOwnerAndProgress() throws Exception {
        tasksOnShare.add(task(42L, 5, 10));
        refreshNow();
        assertEquals(1, heard.size());
        InventoryBadges.Mark mark = heard.get(0).get(42L);
        assertEquals("owner", mark.clientId);
        assertEquals(0.5f, mark.progress, 0.0001f);
    }

    /** An unchanged answer is not re-delivered — a delivery is a round of redraw checks. */
    @Test
    public void unchangedMarksAreNotRedelivered() throws Exception {
        tasksOnShare.add(task(42L, 5, 10));
        refreshNow();
        refreshNow();
        assertEquals(1, heard.size());
        tasksOnShare.clear();
        tasksOnShare.add(task(42L, 6, 10));
        refreshNow();
        assertEquals(2, heard.size());
    }

    /** SMB off means empty marks, delivered once, without touching the share. */
    @Test
    public void disabledSmbClearsTheMarks() throws Exception {
        tasksOnShare.add(task(42L, 5, 10));
        refreshNow();
        Settings.putBoolean(Settings.KEY_SMB_SAVE_ENABLED, false);
        refreshNow();
        assertEquals(2, heard.size());
        assertTrue(heard.get(1).isEmpty());
    }

    /** An unknown total is an empty ring, not a full one. */
    @Test
    public void unknownTotalReadsAsZeroProgress() {
        assertEquals(0f, InventoryBadges.fractionOf(task(1L, 3, 0)), 0.0001f);
    }
}
