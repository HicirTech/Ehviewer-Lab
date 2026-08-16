/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.NetworkStorage;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The move flag's lifecycle (#140): "move to SMB" deletes the phone copy on finish, so the flag
 * must exist exactly while a move the user asked for is still on its way to finishing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbTaskLedgerTest {

    private final GalleryInfo gallery = NetworkStorage.lookupKey(42L, "Answer");

    @org.junit.Before
    public void setUp() {
        // clientState reads the client id and device name from Settings.
        com.hippo.ehviewer.Settings.initialize(org.robolectric.RuntimeEnvironment.getApplication());
    }

    /** The positive control: an untouched move does drop the phone copy. */
    @Test
    public void aCompletedMoveIsAMove() {
        SmbTaskLedger ledger = new SmbTaskLedger();
        assertTrue(ledger.enqueue(gallery, true));
        assertTrue(ledger.finish(gallery).wasMove);
    }

    @Test
    public void aCancelledMoveDoesNotTurnALaterSaveIntoAMove() {
        SmbTaskLedger ledger = new SmbTaskLedger();
        assertTrue(ledger.enqueue(gallery, true));
        ledger.cancel(gallery.gid);
        assertTrue(ledger.enqueue(gallery, false));
        assertFalse("the cancelled move's deletion leaked into a plain save",
                ledger.finish(gallery).wasMove);
    }

    /** A move rejected by dedup changed nothing — including the running plain save's outcome. */
    @Test
    public void aRejectedMoveDoesNotFlagTheRunningSave() {
        SmbTaskLedger ledger = new SmbTaskLedger();
        assertTrue(ledger.enqueue(gallery, false));
        assertFalse(ledger.enqueue(gallery, true));
        assertFalse("a rejected move must not delete the phone copy",
                ledger.finish(gallery).wasMove);
    }

    /** Another device finishes a yielded task; this device must not delete anything later. */
    @Test
    public void aYieldedMoveDegradesToACopy() {
        SmbTaskLedger ledger = new SmbTaskLedger();
        assertTrue(ledger.enqueue(gallery, true));
        ledger.yield(gallery.gid);
        assertTrue(ledger.enqueue(gallery, false));
        assertFalse(ledger.finish(gallery).wasMove);
    }

    /**
     * A start that failed after leaving the queue must not linger as a claim (#151): a later
     * re-enqueue gets a FRESH claim time, not the leaked one — claim times arbitrate takeovers
     * across devices, and an ancient one skews them.
     */
    @Test
    public void aFailedStartForgetsItsClaim() throws Exception {
        SmbTaskLedger ledger = new SmbTaskLedger();
        assertTrue(ledger.enqueue(gallery, false));
        assertNotNull(ledger.nextToStart(3));

        ledger.forgetFailedStart(gallery.gid);

        Thread.sleep(5);
        long beforeRetry = System.currentTimeMillis();
        assertTrue(ledger.enqueue(gallery, false));
        assertTrue("the retry must claim afresh, not inherit the failed start's stamp",
                ledger.clientState().tasks.get(0).claimedAt >= beforeRetry);
    }

    /** Pause is not cancel: the user still wants the move once it resumes and finishes. */
    @Test
    public void aPausedMoveStaysAMove() {
        SmbTaskLedger ledger = new SmbTaskLedger();
        assertTrue(ledger.enqueue(gallery, true));
        ledger.pause(gallery.gid);
        ledger.takeOutPaused(gallery.gid);
        assertTrue(ledger.enqueue(gallery, false));
        assertTrue(ledger.finish(gallery).wasMove);
    }
}
