/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.Hosts;
import com.hippo.ehviewer.Settings;

import java.net.Inet4Address;
import java.net.InetAddress;

import okhttp3.HttpUrl;

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

    /** Empty when unset or when {@link #isAllowedUrl} refuses it. */
    @NonNull
    public static String getSuperResolutionUrl() {
        String url = normalizeUrl(Settings.getString(KEY_SR_URL, ""));
        return isAllowedUrl(url) ? url : "";
    }

    /** https to any host; plain http only to a LAN address: 10/8, 172.16/12 or 192.168/16. */
    public static boolean isAllowedUrl(@NonNull String url) {
        HttpUrl parsed = HttpUrl.parse(url.trim());
        if (parsed == null) {
            return false;
        }
        if (parsed.isHttps()) {
            return true;
        }
        InetAddress address = Hosts.toInetAddress(parsed.host(), parsed.host());
        return address instanceof Inet4Address && address.isSiteLocalAddress();
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
