package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-gid folder listing, cached seconds at a time. Going stale by time is safe; going stale by
 * an own write is #35 — so every write must invalidate. Keyed by gid: folders rename, gids don't.
 */
final class GalleryListingCache {

    // Short enough for other devices' additions, long enough to scan a gallery on one listing.
    static final long DEFAULT_TTL_MS = 5_000L;

    private final long ttlMs;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    /**
     * Confirmed writes since the last put, kept aside: a list() spans a network round trip, and
     * a page published inside that window is missing from the snapshot the put installs. The
     * union is always safe — every name here was confirmed by the share.
     */
    private final Map<Long, Set<String>> pending = new ConcurrentHashMap<>();

    GalleryListingCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    private static final class Entry {
        final long fetchedAt;
        @NonNull final Set<String> names;

        Entry(long fetchedAt, @NonNull Set<String> names) {
            this.fetchedAt = fetchedAt;
            this.names = names;
        }
    }

    /** The remembered listing, or null. Clock is an argument (testable). */
    @Nullable
    Set<String> get(long gid, long nowMillis) {
        Entry e = entries.get(gid);
        if (e == null || nowMillis - e.fetchedAt >= ttlMs) {
            return null;
        }
        return e.names;
    }

    void put(long gid, @NonNull Set<String> names, long nowMillis) {
        // Entry first, drain second: a noteWritten racing this either lands in pending before
        // the drain, or finds the installed entry — no ordering loses the name (#151).
        entries.put(gid, new Entry(nowMillis, names));
        Set<String> noted = pending.remove(gid);
        if (noted != null) {
            for (String name : noted) {
                noteEntry(gid, name);
            }
        }
    }

    /**
     * Forgets this gallery's listing. Call after anything that changes the folder in a way this
     * cache was not told about — deleting the gallery, renaming it, a failed write.
     */
    void invalidate(long gid) {
        entries.remove(gid);
        // Anything noted so far predates the next listing, which will see it on the share.
        pending.remove(gid);
    }

    /**
     * Records one confirmed-written file (#102). Copy-on-write — the cached set is handed out to
     * readers. fetchedAt is kept: the rest of the listing still ages from its snapshot time.
     * Incremental change only ever adds a name; everything uncertain goes through invalidate.
     */
    void noteWritten(long gid, @NonNull String name) {
        pending.computeIfAbsent(gid, key -> ConcurrentHashMap.newKeySet()).add(name);
        noteEntry(gid, name);
    }

    private void noteEntry(long gid, @NonNull String name) {
        entries.computeIfPresent(gid, (key, e) -> {
            Set<String> names = new java.util.HashSet<>(e.names);
            names.add(name);
            return new Entry(e.fetchedAt, names);
        });
    }
}
