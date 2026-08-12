package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * When a leftover on the share may be deleted (#75).
 *
 * <p>This decides whether one device removes a file another device may still be writing. Getting it
 * wrong in one direction leaves litter; in the other it corrupts somebody's write — including, in
 * {@code state/}, a file this class has no other business touching. So the rule is stated here
 * rather than left to a threshold nobody re-reads.
 *
 * <p>Both clocks are arguments, so none of this is waited out.
 */
public class SmbTempFilesTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long OLD_ENOUGH = SmbTempFiles.ABANDONED_AFTER_MS;

    @Test
    public void aTemporaryLeftLongEnoughIsAbandoned() {
        assertTrue(SmbTempFiles.isAbandoned("d3766bd4f2261b5f.124081439544314.tmp",
                NOW - OLD_ENOUGH, NOW));
    }

    /** The writer of a temporary this new may be mid-rename, and the file is its only copy. */
    @Test
    public void aTemporaryStillWithinTheWindowIsLeftAlone() {
        assertFalse(SmbTempFiles.isAbandoned("d3766bd4f2261b5f.124081439544314.tmp",
                NOW - OLD_ENOUGH + 1, NOW));
    }

    /**
     * The one that matters most. Everything published lives beside these temporaries — in
     * {@code state/} another device's whole queue, in a gallery folder its pages — and none of it
     * ages out of existence. A sweep that went by age alone would empty the share.
     */
    @Test
    public void nothingThatIsNotATemporaryIsEverAbandoned() {
        long ancient = NOW - 400L * 24 * 60 * 60 * 1000;

        assertFalse("another device's published state",
                SmbTempFiles.isAbandoned("d3766bd4f2261b5f.json", ancient, NOW));
        assertFalse("a saved page", SmbTempFiles.isAbandoned("00000007.jpg", ancient, NOW));
        assertFalse("a gallery's own record", SmbTempFiles.isAbandoned("metadata.json", ancient, NOW));
        assertFalse("a cover", SmbTempFiles.isAbandoned("cover.jpg", ancient, NOW));
        assertFalse("something a user put there", SmbTempFiles.isAbandoned("notes.txt", ancient, NOW));
    }

    /** No usable timestamp means no way to tell, and "delete it" is the answer that cannot be undone. */
    @Test
    public void aTemporaryWithNoTimestampIsLeftAlone() {
        assertFalse(SmbTempFiles.isAbandoned("x.1.tmp", 0L, NOW));
        assertFalse(SmbTempFiles.isAbandoned("x.1.tmp", -1L, NOW));
    }

    /**
     * A file dated after this device's clock means the share and this device disagree about the
     * time, not that the file is impossibly new. Same direction as the liveness rule next door:
     * when the clocks argue, assume somebody is still working.
     */
    @Test
    public void aTemporaryDatedInTheFutureIsLeftAlone() {
        assertFalse(SmbTempFiles.isAbandoned("x.1.tmp", NOW + 60_000L, NOW));
    }

    /**
     * The sweep only finds what the writer named, so the two have to keep agreeing. Split them and
     * nothing fails — temporaries just quietly stop being collected again.
     */
    @Test
    public void whatTheWriterNamesIsWhatTheSweepRecognises() {
        String name = SmbTempFiles.nameFor("00000007.jpg");

        assertTrue("the base has to stay legible to anyone looking at the share",
                name.startsWith("00000007.jpg"));
        assertTrue(SmbTempFiles.isAbandoned(name, NOW - OLD_ENOUGH, NOW));
    }

    /**
     * Two writes of one file must not share a temporary: the loser's close would land on the
     * winner's rename. Also why a temporary left by a dead process is never mistaken for a live one.
     */
    @Test
    public void everyTemporaryNameIsItsOwn() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            names.add(SmbTempFiles.nameFor("00000007.jpg"));
        }

        assertTrue("names collided: " + (1000 - names.size()) + " of 1000", names.size() == 1000);
    }

    /**
     * Guards the threshold itself. A publish gives up after eight seconds and a page is written in
     * one pass, so the window is meant to be minutes — orders past anything merely slow. Tightening
     * it to keep the share tidier is exactly the change that would start deleting live writes, and
     * it would show up as corrupted files on somebody else's device, not here.
     */
    @Test
    public void theWindowStaysFarBeyondAnyWriteThatIsMerelySlow() {
        assertTrue("abandonment window is " + SmbTempFiles.ABANDONED_AFTER_MS + "ms",
                SmbTempFiles.ABANDONED_AFTER_MS >= 60_000L);
    }
}
