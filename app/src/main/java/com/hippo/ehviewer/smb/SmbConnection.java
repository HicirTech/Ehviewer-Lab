package com.hippo.ehviewer.smb;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Settings;

import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * Protocol-specific floor: credentials, signing, jcifs contexts, share URLs. Nothing above this
 * class knows it is talking SMB — the #100 boundary.
 */
public final class SmbConnection {

    private static final String TAG = "SmbStorage";

    private SmbConnection() {}

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(Settings.getSmbHost()) &&
                !TextUtils.isEmpty(Settings.getSmbShareName());
    }

    /** Base context plus NTLM credentials from settings, when a username is set. */
    @NonNull
    static CIFSContext buildContext() {
        CIFSContext base = baseContext();
        String username = Settings.getSmbUsername();
        if (TextUtils.isEmpty(username)) {
            return base;
        }
        NtlmPasswordAuthenticator authenticator =
                new NtlmPasswordAuthenticator(null, username, Settings.getSmbPassword());
        return base.withCredentials(authenticator);
    }

    // One cached base context so jcifs' connection pool stays shared; rebuilt only when the
    // signing setting flips (the no-signing path needs its own PropertyConfiguration). Context
    // and flag travel as one volatile pair — read separately, a mid-flip caller could pair the
    // new context with the stale flag and get the wrong signing mode (#143).
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
        boolean signingDisabled = Settings.getSmbSigningDisabled();
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

    /**
     * The replaced context's transport pool held real sockets that used to leak. Closed after a
     * grace period so requests already running on it finish rather than die mid-call; the shared
     * SingletonContext is never closed.
     */
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
            // ipcSigningEnforced defaults to true and is the one that matters; the other two are
            // explicit no-ops.
            props.setProperty("jcifs.smb.client.signingPreferred", "false");
            props.setProperty("jcifs.smb.client.signingEnforced", "false");
            props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
            return new BaseContext(new PropertyConfiguration(props));
        } catch (Throwable e) {
            Log.e(TAG, "Failed to build no-signing CIFS context; using default", e);
            return SingletonContext.getInstance();
        }
    }

    /** The {@code download/} root galleries live under. */
    @NonNull
    static String galleryRootUrl() {
        return SmbPaths.buildGalleryRootUrl(buildSmbUrl());
    }

    /** The configured share path itself. */
    @NonNull
    static String buildSmbUrl() {
        return SmbPaths.buildShareUrl(
                Settings.getSmbHost(),
                Settings.getSmbPort(),
                Settings.getSmbShareName(),
                Settings.getSmbSharePath());
    }

}
