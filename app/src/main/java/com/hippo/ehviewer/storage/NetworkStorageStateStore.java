/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import androidx.annotation.NonNull;

import java.util.List;

/**
 * The multi-client download board's persistence (#59): one file per client, only its owner
 * writes it, the file's mtime is the heartbeat. Blocking IO; worker threads.
 */
public interface NetworkStorageStateStore {

    /** Every client's published state; liveness decided from mtime. Unreadable files skipped. */
    @NonNull
    List<DownloadState.Published> readAll();

    /** Publishes this client's state atomically. */
    boolean writeSelf(@NonNull DownloadState.ClientState state);

    /**
     * Removes a gallery from another client's file — the single, narrow exception to "only the
     * owner writes", valid only against an owner stale past {@link DownloadState#STALE_AFTER_MS}.
     */
    boolean removeTask(@NonNull String ownerClientId, long gid);
}
