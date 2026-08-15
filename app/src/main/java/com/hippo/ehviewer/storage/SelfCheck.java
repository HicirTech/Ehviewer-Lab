/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What a pre-save probe of a {@link ConnectionDraft} found: three cumulative stages. A later
 * stage can only pass if the ones before it did.
 */
public final class SelfCheck {
    public final boolean connectOk;
    public final boolean readOk;
    public final boolean writeOk;
    /** The first failing stage's cause, user-facing; null when everything passed. */
    @Nullable public final String failure;

    public SelfCheck(boolean connectOk, boolean readOk, boolean writeOk,
                     @Nullable String failure) {
        this.connectOk = connectOk;
        this.readOk = readOk;
        this.writeOk = writeOk;
        this.failure = failure;
    }

    public boolean allOk() {
        return connectOk && readOk && writeOk;
    }

    /** Readable but not writable — the browse-only case the save flow asks about. */
    public boolean readOnly() {
        return connectOk && readOk && !writeOk;
    }

    @NonNull
    public static SelfCheck failedToConnect(@Nullable String cause) {
        return new SelfCheck(false, false, false, cause);
    }
}
