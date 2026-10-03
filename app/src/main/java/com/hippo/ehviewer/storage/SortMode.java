package com.hippo.ehviewer.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.data.GalleryInfo;

import com.hippo.ehviewer.storage.SortMode;
import java.util.Comparator;

/** Persisted as ordinal(): keep the declaration order. */
public enum SortMode {
    DOWNLOAD_DATE_DESC,
    POSTED_DATE_DESC,
    TITLE_ASC,
    CATEGORY;

    @NonNull
    public static SortMode fromOrdinal(int o) {
        SortMode[] all = values();
        return o >= 0 && o < all.length ? all[o] : DOWNLOAD_DATE_DESC;
    }

    /** {@code downloadedAtMillis} is the metadata.json mtime, 0 when unknown. */
    public static final class Entry {
        @NonNull public final GalleryInfo info;
        public final long downloadedAtMillis;

        public Entry(@NonNull GalleryInfo info, long downloadedAtMillis) {
            this.info = info;
            this.downloadedAtMillis = downloadedAtMillis;
        }
    }

    @NonNull
    public Comparator<Entry> comparator() {
        switch (this) {
            case POSTED_DATE_DESC:
                // posted reads like "2024-01-15 12:34", so string order is date order.
                return (a, b) -> postedOf(b.info).compareTo(postedOf(a.info));
            case TITLE_ASC:
                return (a, b) -> titleOf(a.info).compareToIgnoreCase(titleOf(b.info));
            case CATEGORY:
                return (a, b) -> {
                    int diff = Integer.compare(a.info.category, b.info.category);
                    if (diff != 0) {
                        return diff;
                    }
                    return titleOf(a.info).compareToIgnoreCase(titleOf(b.info));
                };
            case DOWNLOAD_DATE_DESC:
            default:
                return (a, b) -> Long.compare(b.downloadedAtMillis, a.downloadedAtMillis);
        }
    }

    @NonNull
    private static String postedOf(@NonNull GalleryInfo gi) {
        return gi.posted != null ? gi.posted : "";
    }

    @NonNull
    static String titleOf(@Nullable GalleryInfo gi) {
        if (gi == null) {
            return "";
        }
        if (gi.title != null) {
            return gi.title;
        }
        if (gi.titleJpn != null) {
            return gi.titleJpn;
        }
        return "";
    }
}
