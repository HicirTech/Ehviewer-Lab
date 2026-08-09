package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * When one device decides another has gone away.
 *
 * <p>This is the only judgement in {@link SmbDownloadStateStore} that does not need a share, and
 * it is the one worth pinning: getting it wrong in one direction hands a running device's download
 * to someone else, and in the other leaves a crashed device's work unrecoverable. Both failures are
 * silent.
 *
 * <p>The evidence is the state file's mtime, which comes from the server, weighed against this
 * device's clock. Nothing guarantees the two agree.
 */
public class SmbDownloadStateStoreTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void aFileJustWrittenIsAlive() {
        assertTrue(SmbDownloadStateStore.isAlive(NOW, NOW));
    }

    @Test
    public void aFileWithinTheWindowIsAlive() {
        assertTrue(SmbDownloadStateStore.isAlive(
                NOW - SmbDownloadStateStore.STALE_AFTER_MS + 1, NOW));
    }

    @Test
    public void aFileOlderThanTheWindowIsStale() {
        assertFalse(SmbDownloadStateStore.isAlive(
                NOW - SmbDownloadStateStore.STALE_AFTER_MS - 1, NOW));
    }

    /** Exactly at the boundary counts as stale, so the window is a closed answer either way. */
    @Test
    public void theBoundaryItselfIsStale() {
        assertFalse(SmbDownloadStateStore.isAlive(
                NOW - SmbDownloadStateStore.STALE_AFTER_MS, NOW));
    }

    /**
     * A timestamp ahead of this device's clock means the two disagree about the time, not that the
     * file is impossibly old. Reading it as stale would let a device with a slow clock declare
     * every other device dead and start taking over downloads that are actively running.
     */
    @Test
    public void aFileDatedInTheFutureIsAlive() {
        assertTrue(SmbDownloadStateStore.isAlive(NOW + 60_000L, NOW));
        assertTrue("a wildly skewed clock is still a disagreement, not staleness",
                SmbDownloadStateStore.isAlive(NOW + 86_400_000L, NOW));
    }

    /**
     * No usable timestamp at all. Assuming alive would make the tasks in that file permanently
     * unrecoverable, since nothing would ever be allowed to take them over — the worse of the two
     * mistakes, so this resolves the other way.
     */
    @Test
    public void aFileWithNoTimestampIsStale() {
        assertFalse(SmbDownloadStateStore.isAlive(0L, NOW));
        assertFalse(SmbDownloadStateStore.isAlive(-1L, NOW));
    }
}
