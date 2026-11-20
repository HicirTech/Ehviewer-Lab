/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.Settings;

/** The homelab services: one password for all of them, and super-resolution while reading (#207). */
public final class HomelabSettings {

    /** HOMELAB_PASS of the companion's docker compose. */
    public static final String KEY_PASSWORD = "homelab_password";
    public static final String KEY_SR_ENABLED = "homelab_sr_enabled";
    /** Upscale on this device instead of the homelab; the device side comes with #208. */
    public static final String KEY_SR_LOCAL = "homelab_sr_local";
    public static final String KEY_SR_URL = "homelab_sr_url";

    private HomelabSettings() {}

    @NonNull
    public static String getPassword() {
        return Settings.getString(KEY_PASSWORD, "");
    }

    @NonNull
    public static String getSuperResolutionUrl() {
        return normalizeUrl(Settings.getString(KEY_SR_URL, ""));
    }

    /** Pages go to the homelab only when super-resolution is on, not local, and has a URL. */
    public static boolean sendsPagesToHomelab() {
        return Settings.getBoolean(KEY_SR_ENABLED, false)
                && !Settings.getBoolean(KEY_SR_LOCAL, false)
                && !getSuperResolutionUrl().isEmpty();
    }

    @NonNull
    static String normalizeUrl(@NonNull String url) {
        String trimmed = url.trim();
        int end = trimmed.length();
        while (end > 0 && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(0, end);
    }
}
