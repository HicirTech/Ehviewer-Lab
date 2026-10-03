package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Every own write must be noted or invalidated here; staleness by time is safe. */
final class GalleryListingCache {

    // Short enough for other devices' additions, long enough to scan a gallery on one listing.
    static final long DEFAULT_TTL_MS = 5_000L;

    private final long ttlMs;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    /** Writes noted since the last put, which a list() in flight may have missed. */
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

    @Nullable
    Set<String> get(long gid, long nowMillis) {
        Entry e = entries.get(gid);
        if (e == null || nowMillis - e.fetchedAt >= ttlMs) {
            return null;
        }
        return e.names;
    }

    void put(long gid, @NonNull Set<String> names, long nowMillis) {
        // Entry before drain: a racing noteWritten lands in pending or in the entry.
        entries.put(gid, new Entry(nowMillis, names));
        Set<String> noted = pending.remove(gid);
        if (noted != null) {
            for (String name : noted) {
                noteEntry(gid, name);
            }
        }
    }

    void invalidate(long gid) {
        entries.remove(gid);
        // The next listing sees whatever was noted so far.
        pending.remove(gid);
    }

    /** Confirmed writes only; copy-on-write, since readers hold the cached set. */
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
