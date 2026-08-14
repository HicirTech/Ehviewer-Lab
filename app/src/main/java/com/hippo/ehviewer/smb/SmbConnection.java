package com.hippo.ehviewer.smb;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

import java.io.IOException;
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
    // signing setting flips (the no-signing path needs its own PropertyConfiguration).
    private static volatile CIFSContext sBaseContext;
    private static volatile boolean sBaseSigningDisabled;

    @NonNull
    private static CIFSContext baseContext() {
        boolean signingDisabled = Settings.getSmbSigningDisabled();
        CIFSContext base = sBaseContext;
        if (base != null && sBaseSigningDisabled == signingDisabled) {
            return base;
        }
        synchronized (SmbConnection.class) {
            if (sBaseContext == null || sBaseSigningDisabled != signingDisabled) {
                sBaseContext = signingDisabled ? buildNoSigningContext() : SingletonContext.getInstance();
                sBaseSigningDisabled = signingDisabled;
            }
            return sBaseContext;
        }
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

    /**
     * Verifies the share is reachable and creates the gallery root. Creation failure is a
     * warning, not an error — a read-only share still browses.
     *
     * @return null when everything is in place, otherwise a user-facing warning.
     */
    @Nullable
    public static String testConnection() throws IOException {
        String host = Settings.getSmbHost();
        String shareName = Settings.getSmbShareName();

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(shareName)) {
            throw new IOException(EhApplication.getInstance()
                    .getString(R.string.smb_test_error_unconfigured));
        }

        CIFSContext cifs = buildContext();
        SmbFile shareRoot = new SmbFile(buildSmbUrl(), cifs);
        if (!shareRoot.exists()) {
            throw new IOException(EhApplication.getInstance()
                    .getString(R.string.smb_test_error_share_not_accessible));
        }

        try {
            SmbFile galleryRoot = new SmbFile(galleryRootUrl(), cifs);
            if (!galleryRoot.exists()) {
                galleryRoot.mkdirs();
            }
        } catch (Throwable e) {
            Log.w(TAG, "Share is reachable but " + SmbPaths.GALLERY_DIR + "/ could not be created", e);
            return EhApplication.getInstance()
                    .getString(R.string.smb_test_warn_gallery_dir, SmbPaths.GALLERY_DIR);
        }
        return null;
    }
}
