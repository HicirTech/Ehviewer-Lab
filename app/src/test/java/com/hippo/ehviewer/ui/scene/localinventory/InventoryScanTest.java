/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.GalleryCheck;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Which scanned galleries are listed, how each one reads and which a repair takes (#203). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class InventoryScanTest {

    private static GalleryCheck gallery(long gid, String token, int expected, int missing, boolean coverMissing) {
        GalleryInfo info = new GalleryInfo();
        info.gid = gid;
        info.token = token;
        info.title = "G" + gid;
        return new GalleryCheck(gid + "-G" + gid, "G" + gid, info, expected, missing, false, coverMissing);
    }

    @Test
    public void onlyGalleriesWithAProblemAreListedInScanOrder() {
        GalleryCheck whole = gallery(1, "t", 10, 0, false);
        GalleryCheck holes = gallery(2, "t", 10, 3, false);
        GalleryCheck unknown = new GalleryCheck("3-X", "X", null, GalleryCheck.UNKNOWN_PAGES, 0, true, false);

        List<InventoryScan.Row> rows =
                InventoryScan.rowsOf(Arrays.asList(whole, holes, unknown), Set.of(), Set.of());

        assertEquals(2, rows.size());
        assertSame(holes, rows.get(0).check);
        assertSame(unknown, rows.get(1).check);
    }

    @Test
    public void eachRowSaysWhatKeepsItFromARepair() {
        List<InventoryScan.Row> rows = InventoryScan.rowsOf(Arrays.asList(
                gallery(1, "t", 10, 1, false),
                gallery(2, null, 10, 1, false),
                gallery(3, "t", 10, 1, false),
                gallery(4, "t", 10, 1, false)), Set.of(3L), Set.of(4L));

        assertEquals(InventoryScan.Blocker.NONE, rows.get(0).blocker);
        assertEquals(InventoryScan.Blocker.NO_TOKEN, rows.get(1).blocker);
        assertEquals(InventoryScan.Blocker.SAVING_HERE, rows.get(2).blocker);
        assertEquals(InventoryScan.Blocker.SAVING_ELSEWHERE, rows.get(3).blocker);
    }

    @Test
    public void aRowShowsTheExpectedAndMissingPagesThenTheTitle() {
        Context context = RuntimeEnvironment.getApplication();
        InventoryScan.Row row = InventoryScan.rowsOf(
                Collections.singletonList(gallery(7, "t", 85, 22, true)), Set.of(), Set.of()).get(0);

        String[] lines = InventoryScan.describe(context, row).split("\n");

        assertTrue(lines[0].startsWith(context.getString(R.string.local_inventory_scan_pages, 85, 22)));
        assertTrue(lines[0].contains(context.getString(R.string.local_inventory_scan_no_cover)));
        assertEquals("G7", lines[1]);
    }

    @Test
    public void aRepairTakesOnlyTheRowsNothingBlocks() {
        List<InventoryScan.Row> rows = InventoryScan.rowsOf(Arrays.asList(
                gallery(1, "t", 10, 1, false),
                gallery(2, null, 10, 1, false),
                gallery(3, "t", 10, 1, false)), Set.of(3L), Set.of());

        List<GalleryInfo> picked = InventoryScan.repairable(rows);

        assertEquals(1, picked.size());
        assertEquals(1L, picked.get(0).gid);
    }
}
