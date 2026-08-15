/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import androidx.annotation.NonNull;

/**
 * A connection configuration that is not (yet) the live one: what the settings page holds while
 * the user edits, and what {@link NetworkStorage#selfCheck} probes before anything is persisted.
 */
public final class ConnectionDraft {
    @NonNull public final String host;
    @NonNull public final String port;
    @NonNull public final String shareName;
    @NonNull public final String sharePath;
    @NonNull public final String username;
    @NonNull public final String password;
    public final boolean signingDisabled;

    public ConnectionDraft(@NonNull String host, @NonNull String port,
                           @NonNull String shareName, @NonNull String sharePath,
                           @NonNull String username, @NonNull String password,
                           boolean signingDisabled) {
        this.host = host;
        this.port = port;
        this.shareName = shareName;
        this.sharePath = sharePath;
        this.username = username;
        this.password = password;
        this.signingDisabled = signingDisabled;
    }
}
