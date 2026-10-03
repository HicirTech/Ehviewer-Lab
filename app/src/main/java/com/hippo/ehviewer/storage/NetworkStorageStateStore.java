/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import androidx.annotation.NonNull;

import java.util.List;

/** One file per client, only its owner writes it, mtime is the heartbeat; blocking IO. */
public interface NetworkStorageStateStore {

    /** Unreadable files are skipped. */
    @NonNull
    List<DownloadState.Published> readAll();

    /** Publishes this client's state atomically. */
    boolean writeSelf(@NonNull DownloadState.ClientState state);

    /** Only against an owner stale past {@link DownloadState#STALE_AFTER_MS}. */
    boolean removeTask(@NonNull String ownerClientId, long gid);
}
