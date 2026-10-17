/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.ListUrlBuilder;
import com.hippo.ehviewer.widget.AdvanceSearchTable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A search of the library, read from what the online search layout fills in; pure, no IO. */
public final class InventoryQuery {

    private static final String UPLOADER = "uploader";

    private static final Map<String, String> SHORT_NAMESPACES = new HashMap<>();
    private static final Set<String> NAMESPACES = new HashSet<>(Arrays.asList(UPLOADER, "misc"));

    static {
        SHORT_NAMESPACES.put("a", "artist");
        SHORT_NAMESPACES.put("c", "character");
        SHORT_NAMESPACES.put("cos", "cosplayer");
        SHORT_NAMESPACES.put("f", "female");
        SHORT_NAMESPACES.put("g", "group");
        SHORT_NAMESPACES.put("l", "language");
        SHORT_NAMESPACES.put("loc", "location");
        SHORT_NAMESPACES.put("m", "male");
        SHORT_NAMESPACES.put("o", "other");
        SHORT_NAMESPACES.put("p", "parody");
        SHORT_NAMESPACES.put("r", "reclass");
        SHORT_NAMESPACES.put("x", "mixed");
        NAMESPACES.addAll(SHORT_NAMESPACES.values());
    }

    private static final int NOT_SET = -1;

    private final int mode;
    @NonNull
    private final List<Term> terms;
    /** The bits of the categories to leave out, as the online search sends them. */
    private final int excludedCategories;
    private final boolean titles;
    private final boolean tags;
    private final boolean uploader;
    private final int minRating;
    private final int pageFrom;
    private final int pageTo;

    private InventoryQuery(int mode, @NonNull List<Term> terms, int excludedCategories,
                           boolean titles, boolean tags, boolean uploader,
                           int minRating, int pageFrom, int pageTo) {
        this.mode = mode;
        this.terms = terms;
        this.excludedCategories = excludedCategories;
        this.titles = titles;
        this.tags = tags;
        this.uploader = uploader;
        this.minRating = minRating;
        this.pageFrom = pageFrom;
        this.pageTo = pageTo;
    }

    /** Subscription and image searches need the site. */
    public static boolean supports(int mode) {
        return mode == ListUrlBuilder.MODE_NORMAL || mode == ListUrlBuilder.MODE_UPLOADER
                || mode == ListUrlBuilder.MODE_TAG;
    }

    @NonNull
    public static InventoryQuery from(@NonNull ListUrlBuilder builder) {
        int mode = builder.getMode();
        if (!supports(mode)) {
            throw new IllegalArgumentException("Not a library search: mode " + mode);
        }
        String keyword = builder.getKeyword() != null ? builder.getKeyword().trim() : "";
        List<Term> terms = new ArrayList<>();
        if (mode == ListUrlBuilder.MODE_UPLOADER) {
            if (!keyword.isEmpty()) {
                terms.add(new Term(UPLOADER, lower(keyword), true, false));
            }
        } else if (mode == ListUrlBuilder.MODE_TAG) {
            // A tag search names one tag, spaces and all.
            Term tag = parseTerm(keyword.replace("\"", ""));
            if (tag != null) {
                terms.add(tag);
            }
        } else {
            for (String token : tokens(keyword)) {
                Term term = parseTerm(token);
                if (term != null) {
                    terms.add(term);
                }
            }
        }
        int category = builder.getCategory();
        int excluded = category == EhUtils.NONE ? 0 : ~category & EhConfig.ALL_CATEGORY;
        int advance = builder.getAdvanceSearch();
        boolean byName = advance == NOT_SET || (advance & AdvanceSearchTable.SNAME) != 0;
        boolean byTag = advance == NOT_SET || (advance & AdvanceSearchTable.STAGS) != 0;
        if (!byName && !byTag) {
            byName = true;
            byTag = true;
        }
        boolean tagOnly = mode == ListUrlBuilder.MODE_TAG;
        return new InventoryQuery(mode, terms, excluded,
                byName && !tagOnly, byTag || tagOnly, !tagOnly,
                builder.getMinRating(), builder.getPageFrom(), builder.getPageTo());
    }

    /** Nothing typed and nothing filtered: the whole library, not a search. */
    public boolean isEmpty() {
        return terms.isEmpty() && !hasFilters();
    }

    /** True when titles alone settle it, so folder names can answer before any metadata is read. */
    public boolean answersFromTitles() {
        if (mode != ListUrlBuilder.MODE_NORMAL || !titles || terms.isEmpty() || hasFilters()) {
            return false;
        }
        for (Term term : terms) {
            if (term.namespace != null || term.excluded) {
                return false;
            }
        }
        return true;
    }

    public boolean matchesTitle(@Nullable String title) {
        for (Term term : terms) {
            if (!contains(title, term.value)) {
                return false;
            }
        }
        return true;
    }

    public boolean matches(@NonNull GalleryInfo gi) {
        if ((gi.category & excludedCategories) != 0) {
            return false;
        }
        if (minRating != NOT_SET && gi.rating < minRating) {
            return false;
        }
        if ((pageFrom != NOT_SET && gi.pages < pageFrom) || (pageTo != NOT_SET && gi.pages > pageTo)) {
            return false;
        }
        List<String> tagList = searchableTags(gi);
        for (Term term : terms) {
            if (matches(term, gi, tagList) == term.excluded) {
                return false;
            }
        }
        return true;
    }

    private boolean hasFilters() {
        return excludedCategories != 0 || minRating != NOT_SET
                || pageFrom != NOT_SET || pageTo != NOT_SET;
    }

    private boolean matches(@NonNull Term term, @NonNull GalleryInfo gi, @NonNull List<String> tagList) {
        if (UPLOADER.equals(term.namespace)) {
            return term.exact ? term.value.equals(lowerOrEmpty(gi.uploader)) : contains(gi.uploader, term.value);
        }
        if (term.namespace != null) {
            for (String tag : tagList) {
                if (term.matchesTag(tag)) {
                    return true;
                }
            }
            return false;
        }
        if (titles && (contains(gi.title, term.value) || contains(gi.titleJpn, term.value))) {
            return true;
        }
        if (tags) {
            for (String tag : tagList) {
                if (contains(tag, term.value)) {
                    return true;
                }
            }
        }
        return uploader && contains(gi.uploader, term.value);
    }

    /** The full list when the record has one, as the downloads search reads them. */
    @NonNull
    private static List<String> searchableTags(@NonNull GalleryInfo gi) {
        if (gi.tgList != null && !gi.tgList.isEmpty()) {
            return gi.tgList;
        }
        return gi.simpleTags != null ? Arrays.asList(gi.simpleTags) : Collections.<String>emptyList();
    }

    /** Split on spaces; quotes keep spaces in, as the site reads its search box. */
    @NonNull
    static List<String> tokens(@NonNull String keyword) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** A colon is a namespace only before a known one: "Re:Zero" stays a word of a title. */
    @Nullable
    private static Term parseTerm(@NonNull String token) {
        boolean excluded = token.length() > 1 && token.charAt(0) == '-';
        String body = excluded ? token.substring(1) : token;
        String namespace = null;
        int colon = body.indexOf(':');
        if (colon > 0) {
            String prefix = lower(body.substring(0, colon));
            String full = SHORT_NAMESPACES.get(prefix);
            if (full == null && NAMESPACES.contains(prefix)) {
                full = prefix;
            }
            if (full != null) {
                namespace = full;
                body = body.substring(colon + 1);
            }
        }
        boolean exact = body.endsWith("$");
        if (exact) {
            body = body.substring(0, body.length() - 1);
        }
        String value = lower(body.trim());
        if (value.isEmpty()) {
            return null;
        }
        return new Term(namespace, value, exact, excluded);
    }

    private static boolean contains(@Nullable String text, @NonNull String lowerValue) {
        return text != null && lower(text).contains(lowerValue);
    }

    @NonNull
    private static String lowerOrEmpty(@Nullable String s) {
        return s != null ? lower(s.trim()) : "";
    }

    @NonNull
    private static String lower(@NonNull String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private static final class Term {
        /** Null for a plain word. */
        @Nullable
        final String namespace;
        @NonNull
        final String value;
        final boolean exact;
        final boolean excluded;

        Term(@Nullable String namespace, @NonNull String value, boolean exact, boolean excluded) {
            this.namespace = namespace;
            this.value = value;
            this.exact = exact;
            this.excluded = excluded;
        }

        /** Tags read "namespace:name"; a bare name has no namespace to match. */
        boolean matchesTag(@NonNull String tag) {
            String entry = lower(tag);
            int colon = entry.indexOf(':');
            if (colon <= 0 || !entry.substring(0, colon).equals(namespace)) {
                return false;
            }
            String name = entry.substring(colon + 1);
            return exact ? name.equals(value) : name.contains(value);
        }
    }
}
