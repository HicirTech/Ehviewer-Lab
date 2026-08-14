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
        entries.put(gid, new Entry(nowMillis, names));
    }

    /**
     * Forgets this gallery's listing. Call after anything that changes what the folder contains —
     * writing a page, a cover or metadata, removing a page, deleting the gallery.
     */
    void invalidate(long gid) {
        entries.remove(gid);
    }
}
