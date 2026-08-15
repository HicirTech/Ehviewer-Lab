/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import android.util.Log;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.EhApplication;

import java.io.File;

/**
 * The one directory for anonymous decode shims (cache/smb_tmp). Swept whole on first use each
 * process: shims die with their pipe, so anything found at startup is a leak from a killed
 * process (#131 measured 56 of them).
 */
final class SmbShims {

    private static final String TAG = "SmbShims";

    /** Plantable by tests. */
    private static volatile File sDir;
    private static boolean sSwept;

    private SmbShims() {}

    @NonNull
    static File dir() {
        File dir = sDir;
        if (dir == null) {
            dir = new File(EhApplication.getInstance().getCacheDir(), "smb_tmp");
            sDir = dir;
        }
        sweepOnce(dir);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static synchronized void sweepOnce(@NonNull File dir) {
        if (sSwept) {
            return;
        }
        // Before this process's first shim exists, everything in here is abandoned.
        try {
            File[] leftovers = dir.listFiles();
            if (leftovers != null) {
                for (File f : leftovers) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Could not sweep stale decode shims", e);
        }
        sSwept = true;
    }
}
