/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.storage.ConnectionDraft;
import com.hippo.ehviewer.storage.SelfCheck;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * The pre-save probe (#133): connect, read, write — against the draft's own one-off jcifs
 * context, never the cached pool, so a bad draft cannot poison the live connection state.
 */
final class SmbSelfCheck {

    private static final String TAG = "SmbSelfCheck";

    private SmbSelfCheck() {}

    @NonNull
    static SelfCheck run(@NonNull ConnectionDraft draft) {
        long t0 = android.os.SystemClock.elapsedRealtime();
        SelfCheck out = probe(draft);
        Log.i(TAG, "probe host=" + draft.host + " connect=" + out.connectOk + " read="
                + out.readOk + " write=" + out.writeOk
                + " " + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
        return out;
    }

    @NonNull
    private static SelfCheck probe(@NonNull ConnectionDraft draft) {
        if (TextUtils.isEmpty(draft.host) || TextUtils.isEmpty(draft.shareName)) {
            return SelfCheck.failedToConnect(null);
        }
        CIFSContext owned = ownedBase(draft);
        CIFSContext ctx = withCredentials(
                owned != null ? owned : jcifs.context.SingletonContext.getInstance(), draft);
        try {
            return stages(draft, ctx);
        } finally {
            if (owned != null) {
                try {
                    // The one-off transport pool holds real sockets; jcifs idle reaping is
                    // a fallback, not a plan (#151).
                    owned.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @NonNull
    private static SelfCheck stages(@NonNull ConnectionDraft draft, @NonNull CIFSContext ctx) {
        String shareUrl = SmbPaths.buildShareUrl(
                draft.host, draft.port, draft.shareName, draft.sharePath);

        // Stage 1: the share answers.
        try {
            SmbFile root = new SmbFile(shareUrl, ctx);
            if (!root.exists()) {
                return SelfCheck.failedToConnect(null);
            }
        } catch (Throwable e) {
            return SelfCheck.failedToConnect(SmbErrors.describe(e));
        }

        // Stage 2: its contents can be read.
        try {
            new SmbFile(shareUrl, ctx).list();
        } catch (Throwable e) {
            return new SelfCheck(true, false, false, SmbErrors.describe(e));
        }

        // Stage 3: a temporary file goes in, comes back byte-identical, and goes away.
        SmbFile temp = null;
        try {
            String galleryRootUrl = SmbPaths.buildGalleryRootUrl(shareUrl);
            SmbFile galleryRoot = new SmbFile(galleryRootUrl, ctx);
            if (!galleryRoot.exists()) {
                galleryRoot.mkdirs();
            }
            temp = new SmbFile(galleryRoot, SmbTempFiles.nameFor("selfcheck"));
            byte[] payload = "selfcheck".getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = temp.getOutputStream()) {
                os.write(payload);
            }
            byte[] echo = new byte[payload.length + 1];
            int got;
            try (InputStream is = temp.getInputStream()) {
                got = is.read(echo);
            }
            if (got != payload.length) {
                return new SelfCheck(true, true, false, null);
            }
            for (int i = 0; i < payload.length; i++) {
                if (echo[i] != payload[i]) {
                    return new SelfCheck(true, true, false, null);
                }
            }
            return new SelfCheck(true, true, true, null);
        } catch (Throwable e) {
            return new SelfCheck(true, true, false, SmbErrors.describe(e));
        } finally {
            if (temp != null) {
                try {
                    temp.delete();
                } catch (Throwable ignored) {
                    // A leftover uses the sweepable temp-name pattern (#75).
                }
            }
        }
    }

    /**
     * A one-off context with tight timeouts: a probe must come back while the user is still
     * watching the dialog — jcifs defaults let a black-holed address spin for many minutes
     * (#142). Null when the build failed and the shared SingletonContext (never closed) is
     * the fallback.
     */
    @Nullable
    private static CIFSContext ownedBase(@NonNull ConnectionDraft draft) {
        try {
            Properties props = new Properties();
            props.setProperty("jcifs.smb.client.connTimeout", "10000");
            props.setProperty("jcifs.smb.client.responseTimeout", "10000");
            props.setProperty("jcifs.smb.client.sessionTimeout", "10000");
            if (draft.signingDisabled) {
                props.setProperty("jcifs.smb.client.signingPreferred", "false");
                props.setProperty("jcifs.smb.client.signingEnforced", "false");
                props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
            }
            return new BaseContext(new PropertyConfiguration(props));
        } catch (Throwable e) {
            Log.w(TAG, "Failed to build the probe context", e);
            return null;
        }
    }

    @NonNull
    private static CIFSContext withCredentials(@NonNull CIFSContext base,
                                               @NonNull ConnectionDraft draft) {
        if (TextUtils.isEmpty(draft.username)) {
            return base;
        }
        return base.withCredentials(
                new NtlmPasswordAuthenticator(null, draft.username, draft.password));
    }
}
