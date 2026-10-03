/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.storage;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.provider.Settings.Secure;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.Settings;

import java.util.UUID;

public final class NetworkStorageSettings {

    // Key string predates the umbrella semantics; changing it would cost every user a migration.
    public static final String KEY_ENABLED = "smb_save_enabled";

    /** Absent for users from before the selector: read it through {@link #getProtocol()}. */
    public static final String KEY_PROTOCOL = "storage_protocol";

    /** What the last passed save-probe established about the share (#133). */
    public static final String KEY_LAST_CHECK = "storage_last_check";
    public static final String LAST_CHECK_READ_WRITE = "rw";
    public static final String LAST_CHECK_READ_ONLY = "ro";

    public static final String KEY_SMB_HOST = "smb_host";
    public static final String KEY_SMB_PORT = "smb_port";
    public static final String KEY_SMB_SHARE_NAME = "smb_share_name";
    public static final String KEY_SMB_SHARE_PATH = "smb_share_path";
    public static final String KEY_SMB_USERNAME = "smb_username";
    public static final String KEY_SMB_PASSWORD = "smb_password";
    /** Opt-out of SMB signing, whose per-packet HMAC can slow transfers on weak CPUs. */
    public static final String KEY_SMB_SIGNING_DISABLED = "smb_signing_disabled";
    /** Names this device's {@code state/} file: must be unique and survive clearing app data. */
    public static final String KEY_SMB_CLIENT_ID = "smb_client_id";
    /** Shown to other devices on the share. */
    public static final String KEY_SMB_DEVICE_NAME = "smb_device_name";

    private static final String DEFAULT_SMB_PORT = "445";
    /** Android 2.2 shipped a bug that gave a great many devices this same id. */
    private static final String BROKEN_ANDROID_ID = "9774d56d682e549c";

    @SuppressLint("StaticFieldLeak")
    private static Context sContext;

    private NetworkStorageSettings() {}

    public static void initialize(@NonNull Context context) {
        sContext = context.getApplicationContext();
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(KEY_ENABLED, false);
    }

    @NonNull
    public static String getProtocol() {
        return NetworkStorage.resolveProtocol(Settings.getString(KEY_PROTOCOL, null), getSmbHost());
    }

    @NonNull
    public static String getLastCheck() {
        return Settings.getString(KEY_LAST_CHECK, "");
    }

    @NonNull
    public static String getSmbHost() {
        return Settings.getString(KEY_SMB_HOST, "").trim();
    }

    @NonNull
    public static String getSmbPort() {
        return Settings.getString(KEY_SMB_PORT, DEFAULT_SMB_PORT).trim();
    }

    @NonNull
    public static String getSmbShareName() {
        return Settings.getString(KEY_SMB_SHARE_NAME, "").trim();
    }

    /** Starts and ends with "/". */
    @NonNull
    public static String getSmbSharePath() {
        String path = Settings.getString(KEY_SMB_SHARE_PATH, "/").trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        return path;
    }

    @NonNull
    public static String getSmbUsername() {
        return Settings.getString(KEY_SMB_USERNAME, "");
    }

    @NonNull
    public static String getSmbPassword() {
        return Settings.getString(KEY_SMB_PASSWORD, "");
    }

    public static boolean getSmbSigningDisabled() {
        return Settings.getBoolean(KEY_SMB_SIGNING_DISABLED, false);
    }

    @NonNull
    public static synchronized String getSmbClientId() {
        String androidId = Secure.getString(sContext.getContentResolver(), Secure.ANDROID_ID);
        if (androidId != null) {
            androidId = androidId.trim();
            if (!androidId.isEmpty() && !BROKEN_ANDROID_ID.equals(androidId)) {
                return androidId;
            }
        }
        String stored = Settings.getString(KEY_SMB_CLIENT_ID, "");
        if (stored.isEmpty()) {
            stored = UUID.randomUUID().toString();
            Settings.putString(KEY_SMB_CLIENT_ID, stored);
        }
        return stored;
    }

    @NonNull
    public static String getSmbDeviceName() {
        return smbDeviceNameOrModel(Settings.getString(KEY_SMB_DEVICE_NAME, ""));
    }

    @NonNull
    public static String smbDeviceNameOrModel(@Nullable String name) {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        String model = Build.MODEL;
        return model == null || model.trim().isEmpty() ? "Android" : model.trim();
    }
}
