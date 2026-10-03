package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** When a leftover on the share may be deleted (#75). */
public class SmbTempFilesTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long OLD_ENOUGH = SmbTempFiles.ABANDONED_AFTER_MS;

    @Test
    public void aTemporaryLeftLongEnoughIsAbandoned() {
        assertTrue(SmbTempFiles.isAbandoned("d3766bd4f2261b5f.124081439544314.tmp",
                NOW - OLD_ENOUGH, NOW));
    }

    @Test
    public void aTemporaryStillWithinTheWindowIsLeftAlone() {
        assertFalse(SmbTempFiles.isAbandoned("d3766bd4f2261b5f.124081439544314.tmp",
                NOW - OLD_ENOUGH + 1, NOW));
    }

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

    @Test
    public void aTemporaryWithNoTimestampIsLeftAlone() {
        assertFalse(SmbTempFiles.isAbandoned("x.1.tmp", 0L, NOW));
        assertFalse(SmbTempFiles.isAbandoned("x.1.tmp", -1L, NOW));
    }

    @Test
    public void aTemporaryDatedInTheFutureIsLeftAlone() {
        assertFalse(SmbTempFiles.isAbandoned("x.1.tmp", NOW + 60_000L, NOW));
    }

    @Test
    public void whatTheWriterNamesIsWhatTheSweepRecognises() {
        String name = SmbTempFiles.nameFor("00000007.jpg");

        assertTrue("the base has to stay legible to anyone looking at the share",
                name.startsWith("00000007.jpg"));
        assertTrue(SmbTempFiles.isAbandoned(name, NOW - OLD_ENOUGH, NOW));
    }

    @Test
    public void everyTemporaryNameIsItsOwn() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            names.add(SmbTempFiles.nameFor("00000007.jpg"));
        }

        assertTrue("names collided: " + (1000 - names.size()) + " of 1000", names.size() == 1000);
    }

    @Test
    public void theWindowStaysFarBeyondAnyWriteThatIsMerelySlow() {
        assertTrue("abandonment window is " + SmbTempFiles.ABANDONED_AFTER_MS + "ms",
                SmbTempFiles.ABANDONED_AFTER_MS >= 60_000L);
    }
}
