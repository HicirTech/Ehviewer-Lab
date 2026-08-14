package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** When a device counts as still being there (#59). */
public class SmbDownloadStateStoreTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void aFileWrittenJustNowIsAlive() {
        assertTrue(SmbDownloadStateStore.isAlive(NOW - 1_000L, NOW));
    }

    /**
     * Several missed beats, not one. A heartbeat every 20 seconds against a 90 second window means
     * a congested share or a moment of bad WiFi does not hand somebody's download away.
     */
    @Test
    public void aFileUntouchedPastTheWindowIsNot() {
        assertTrue(SmbDownloadStateStore.isAlive(
                NOW - SmbDownloadStateStore.STALE_AFTER_MS + 1L, NOW));
        assertFalse(SmbDownloadStateStore.isAlive(
                NOW - SmbDownloadStateStore.STALE_AFTER_MS, NOW));
    }

    /** A timestamp ahead of this device's clock means the two disagree about the time, not that the file is impossibly old. */
    @Test
    public void aFileDatedInTheFutureIsAlive() {
        assertTrue(SmbDownloadStateStore.isAlive(NOW + 10 * SmbDownloadStateStore.STALE_AFTER_MS,
                NOW));
    }

    /** No usable timestamp: treating it as alive would make the task permanently unreclaimable. */
    @Test
    public void aFileWithNoTimestampIsNotAlive() {
        assertFalse(SmbDownloadStateStore.isAlive(0L, NOW));
        assertFalse(SmbDownloadStateStore.isAlive(-1L, NOW));
    }
}
