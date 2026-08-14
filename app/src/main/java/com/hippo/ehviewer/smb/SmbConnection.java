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
 * The protocol-specific floor of the SMB layer: configuration, credentials, contexts, URLs (#97).
 *
 * <p>Everything in this class is about <em>reaching</em> the share — NTLM credentials, packet
 * signing, jcifs contexts and their pooling, the {@code smb://host[:port]/share/path} shape —
 * and none of it is about galleries. This is the boundary #100 cares about: an NFS or USB
 * backend replaces this class and nothing above it, because everything above talks in terms of
 * "the remote gallery repository" and only comes here for a context or a root URL.
 *
 * <p>Split out of the old 1494-line {@code SmbStorage}; method bodies are verbatim from there.
 */
public final class SmbConnection {

    private static final String TAG = "SmbStorage";

    private SmbConnection() {}

    public static boolean isConfigured() {
        return !TextUtils.isEmpty(Settings.getSmbHost()) &&
                !TextUtils.isEmpty(Settings.getSmbShareName());
    }

    // Package-private: SmbDownloadStateStore connects to the same share the same way.
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

    // Cached base CIFS context. The default ("auto") path reuses jcifs' SingletonContext so its
    // connection pool stays shared; the "signing disabled" path needs custom config, so it gets its
    // own pooled BaseContext built from a PropertyConfiguration. Rebuilt only when the setting flips.
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
            // jcifs already defaults signingPreferred/signingEnforced to false; setting them keeps
            // that explicit. ipcSigningEnforced defaults to TRUE, so turning it off is the real change
            // — it drops the per-packet HMAC on the control/IPC traffic that signing would otherwise
            // add. Data-share signing beyond this is governed by what the server requires.
            props.setProperty("jcifs.smb.client.signingPreferred", "false");
            props.setProperty("jcifs.smb.client.signingEnforced", "false");
            props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
            return new BaseContext(new PropertyConfiguration(props));
        } catch (Throwable e) {
            // CIFSException (or anything) building the custom config: fall back to the default context
            // rather than break SMB entirely.
            Log.e(TAG, "Failed to build no-signing CIFS context; using default", e);
            return SingletonContext.getInstance();
        }
    }

    /**
     * The share URL galleries live under. {@link #buildSmbUrl} is the configured share path itself,
     * which now holds {@code download/} alongside whatever else the share needs to carry — the
     * per-client download state (#59) and the gallery index (#16) are siblings of the galleries,
     * not entries among them.
     */
    @NonNull
    static String galleryRootUrl() {
        return SmbPaths.buildGalleryRootUrl(buildSmbUrl());
    }

    /**
     * The configured share path itself. Connectivity checks and directory setup use it directly;
     * everything else goes through {@link #galleryRootUrl()} or {@code SmbPaths.buildStateRootUrl}.
     */
    @NonNull
    static String buildSmbUrl() {
        return SmbPaths.buildShareUrl(
                Settings.getSmbHost(),
                Settings.getSmbPort(),
                Settings.getSmbShareName(),
                Settings.getSmbSharePath());
    }

    /**
     * Verifies the configured share is reachable, and sets up the directory galleries live in.
     *
     * <p>Checks the share path itself, not {@code download/} — the share being reachable is the
     * thing being tested, and {@code download/} legitimately does not exist until something
     * creates it.
     *
     * <p>This is also where that directory gets created, because pressing "test connection" is
     * when the user finishes configuring the share, and a write problem is far more useful
     * reported here than at the first download. Creation failing is not a connection failure
     * though: a read-only share browses perfectly well, so it comes back as a warning rather than
     * an exception.
     *
     * @return {@code null} when everything is in place, otherwise a user-facing warning to show
     *         alongside the success message.
     */
    @Nullable
    public static String testConnection() throws IOException {
        String host = Settings.getSmbHost();
        String shareName = Settings.getSmbShareName();

        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(shareName)) {
            // User-facing — surfaced through the Settings toast.
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
