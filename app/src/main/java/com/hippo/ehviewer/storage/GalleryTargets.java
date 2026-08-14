/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The per-gid routing mark: which galleries download to network storage. Local process state,
 * protocol-neutral (a global flag once leaked phone downloads onto the share).
 */
public final class GalleryTargets {

    private static final Set<Long> TARGET_GIDS =
            Collections.synchronizedSet(new HashSet<>());

    private GalleryTargets() {}

    public static void mark(long gid) {
        TARGET_GIDS.add(gid);
    }

    public static void unmark(long gid) {
        TARGET_GIDS.remove(gid);
    }

    public static boolean isMarked(long gid) {
        return TARGET_GIDS.contains(gid);
    }
}
