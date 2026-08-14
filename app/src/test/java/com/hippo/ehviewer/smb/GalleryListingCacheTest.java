package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** When the remembered contents of a gallery folder may be trusted (#35). */
public class GalleryListingCacheTest {

    private static final long GID = 4110713L;
    private static final long TTL = 5_000L;
    private static final long NOW = 1_000_000L;

    private static Set<String> names(String... n) {
        return new HashSet<>(java.util.Arrays.asList(n));
    }

    private GalleryListingCache cache() {
        return new GalleryListingCache(TTL);
    }

    @Test
    public void nothingIsRememberedUntilSomethingIsPut() {
        assertNull(cache().get(GID, NOW));
    }

    @Test
    public void aFreshListingIsServed() {
        GalleryListingCache c = cache();
        c.put(GID, names("00000001.jpg"), NOW);

        assertEquals(names("00000001.jpg"), c.get(GID, NOW));
    }

    /**
     * Going stale by time is the acceptable kind of wrong: the only thing missed is a file another
     * device added, and the next read picks it up.
     */
    @Test
    public void aListingIsForgottenOnceItIsTooOld() {
        GalleryListingCache c = cache();
        c.put(GID, names("00000001.jpg"), NOW);

        assertEquals("still inside the window", names("00000001.jpg"), c.get(GID, NOW + TTL - 1));
        assertNull("past the window", c.get(GID, NOW + TTL));
    }

    /** The rule this class exists for. */
    @Test
    public void invalidatingForgetsEvenAListingTakenThisInstant() {
        GalleryListingCache c = cache();
        c.put(GID, names("00000001.jpg"), NOW);

        c.invalidate(GID);

        assertNull("a write must not be able to leave a stale listing behind", c.get(GID, NOW));
    }

    /** One gallery's writes say nothing about another's contents. */
    @Test
    public void invalidatingOneGalleryLeavesTheOthers() {
        GalleryListingCache c = cache();
        c.put(GID, names("a.jpg"), NOW);
        c.put(GID + 1, names("b.jpg"), NOW);

        c.invalidate(GID);

        assertNull(c.get(GID, NOW));
        assertEquals(names("b.jpg"), c.get(GID + 1, NOW));
    }

    /** An empty folder is a real answer and worth remembering, or a missing gallery is re-probed
     * on every page. */
    @Test
    public void anEmptyListingIsRememberedRatherThanTreatedAsAbsent() {
        GalleryListingCache c = cache();
        c.put(GID, Collections.<String>emptySet(), NOW);

        assertEquals(Collections.<String>emptySet(), c.get(GID, NOW));
    }
}
