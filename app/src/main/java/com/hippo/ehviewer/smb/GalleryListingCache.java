package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers what files a gallery folder on the share contains, for a few seconds at a time.
 *
 * <p>Every "is page N saved?", "is there a cover?", "is this gallery complete?" is answered by
 * looking for a name in this set. Asking the share each time cost about seven round trips per page
 * and made opening a large gallery crawl, so the answer is cached — which means it can be wrong,
 * and the rules about when it is allowed to be wrong are the whole point of this class.
 *
 * <p><b>Going stale by time is safe. Going stale by a write is not.</b> An entry that is a few
 * seconds behind the share only risks missing a file another device has just added, and the next
 * read picks it up. But a file <em>this</em> app writes and then immediately looks for is a
 * different matter: it is not there in the snapshot, and the caller concludes it does not exist.
 * That is #35 — the downloader wrote a page, read it back to check it, was told by a listing taken
 * before the write that it was missing, called the page failed, and deleted the file it had just
 * written. So every write must invalidate.
 *
 * <p>Keyed by gid rather than by path: a gallery's folder can be renamed while its gid cannot.
 */
final class GalleryListingCache {

    /**
     * How long a snapshot may be trusted.
     *
     * <p>Short enough that another device's additions show up quickly, long enough that scanning a
     * gallery does not re-list the folder for every page.
     */
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

    /**
     * The remembered listing, or null if there is none worth trusting.
     *
     * @param nowMillis the caller's clock, passed in so the rule can be stated in a test rather
     *                  than waited out
     */
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
