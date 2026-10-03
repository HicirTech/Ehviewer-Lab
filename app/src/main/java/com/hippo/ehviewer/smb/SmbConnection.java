package com.hippo.ehviewer.smb;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.storage.NetworkStorageSettings;

import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;

public final class SmbConnection {

    private static final String TAG = "SmbStorage";

    private SmbConnection() {}

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(NetworkStorageSettings.getSmbHost()) &&
                !TextUtils.isEmpty(NetworkStorageSettings.getSmbShareName());
    }

    @NonNull
    static CIFSContext buildContext() {
        CIFSContext base = baseContext();
        String username = NetworkStorageSettings.getSmbUsername();
        if (TextUtils.isEmpty(username)) {
            return base;
        }
        NtlmPasswordAuthenticator authenticator =
                new NtlmPasswordAuthenticator(null, username, NetworkStorageSettings.getSmbPassword());
        return base.withCredentials(authenticator);
    }

    // Cached so jcifs' pool stays shared; one volatile pair so context and flag never mismatch.
    private static final class Base {
        @NonNull final CIFSContext ctx;
        final boolean signingDisabled;

        Base(@NonNull CIFSContext ctx, boolean signingDisabled) {
            this.ctx = ctx;
            this.signingDisabled = signingDisabled;
        }
    }

    private static volatile Base sBase;

    @NonNull
    private static CIFSContext baseContext() {
        boolean signingDisabled = NetworkStorageSettings.getSmbSigningDisabled();
        Base base = sBase;
        if (base != null && base.signingDisabled == signingDisabled) {
            return base.ctx;
        }
        synchronized (SmbConnection.class) {
            base = sBase;
            if (base == null || base.signingDisabled != signingDisabled) {
                CIFSContext previous = base == null ? null : base.ctx;
                base = new Base(signingDisabled
                        ? buildNoSigningContext() : SingletonContext.getInstance(), signingDisabled);
                sBase = base;
                closeLater(previous);
            }
            return base.ctx;
        }
    }

    /** After a grace period, so calls still running on the old context can finish. */
    private static void closeLater(@Nullable CIFSContext previous) {
        if (previous == null || previous == SingletonContext.getInstance()) {
            return;
        }
        com.hippo.lib.yorozuya.SimpleHandler.getInstance().postDelayed(() ->
                com.hippo.util.IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
                    try {
                        previous.close();
                    } catch (Throwable e) {
                        Log.w(TAG, "Failed to close the replaced CIFS context", e);
                    }
                }), 30_000L);
    }

    @NonNull
    private static CIFSContext buildNoSigningContext() {
        try {
            Properties props = new Properties();
            // Only ipcSigningEnforced (default true) matters; the other two are explicit no-ops.
            props.setProperty("jcifs.smb.client.signingPreferred", "false");
            props.setProperty("jcifs.smb.client.signingEnforced", "false");
            props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
            return new BaseContext(new PropertyConfiguration(props));
        } catch (Throwable e) {
            Log.e(TAG, "Failed to build no-signing CIFS context; using default", e);
            return SingletonContext.getInstance();
        }
    }

    @NonNull
    static String galleryRootUrl() {
        return SmbPaths.buildGalleryRootUrl(buildSmbUrl());
    }

    /** The configured share path itself. */
    @NonNull
    static String buildSmbUrl() {
        return SmbPaths.buildShareUrl(
                NetworkStorageSettings.getSmbHost(),
                NetworkStorageSettings.getSmbPort(),
                NetworkStorageSettings.getSmbShareName(),
                NetworkStorageSettings.getSmbSharePath());
    }

}
