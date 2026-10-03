/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.download;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.Settings;

/**
 * Power Download's rules (#159): four ways to start a download while reading, each with its own
 * switch and its own preset target.
 */
public final class PowerDownloadSettings {

    public static final String KEY_PAGES_ENABLED = "power_download_pages_enabled";
    public static final String KEY_PAGES_COUNT = "power_download_pages_count";
    public static final String KEY_PAGES_TARGET = "power_download_pages_target";
    public static final String KEY_FIRST_PAGE_ENABLED = "power_download_first_page_enabled";
    public static final String KEY_LAST_PAGE_ENABLED = "power_download_last_page_enabled";
    public static final String KEY_EDGE_PAGE_TARGET = "power_download_edge_page_target";
    public static final String KEY_MENU_ENABLED = "power_download_menu_enabled";
    public static final String KEY_MENU_TARGET = "power_download_menu_target";
    public static final String KEY_VOLUME_ENABLED = "power_download_volume_enabled";
    public static final String KEY_VOLUME_UP_TARGET = "power_download_volume_up_target";
    public static final String KEY_VOLUME_DOWN_TARGET = "power_download_volume_down_target";

    /**
     * Two, the user's call on #159: at one, just opening a gallery would count, as the
     * first-page rule does.
     */
    public static final int DEFAULT_PAGES_COUNT = 2;
    public static final int MIN_PAGES_COUNT = 1;

    private static final PowerDownloadTarget DEFAULT_TARGET = PowerDownloadTarget.PHONE;
    private static final PowerDownloadTarget DEFAULT_VOLUME_UP_TARGET = PowerDownloadTarget.PHONE;
    private static final PowerDownloadTarget DEFAULT_VOLUME_DOWN_TARGET = PowerDownloadTarget.NONE;

    private PowerDownloadSettings() {}

    public static boolean isPagesEnabled() {
        return Settings.getBoolean(KEY_PAGES_ENABLED, false);
    }

    public static int getPagesCount() {
        return Math.max(MIN_PAGES_COUNT, Settings.getIntFromStr(KEY_PAGES_COUNT, DEFAULT_PAGES_COUNT));
    }

    @NonNull
    public static PowerDownloadTarget getPagesTarget() {
        return target(KEY_PAGES_TARGET, DEFAULT_TARGET);
    }

    public static boolean isFirstPageEnabled() {
        return Settings.getBoolean(KEY_FIRST_PAGE_ENABLED, false);
    }

    public static boolean isLastPageEnabled() {
        return Settings.getBoolean(KEY_LAST_PAGE_ENABLED, false);
    }

    /** One target for the first- and last-page rules: they are one way to download, two moments. */
    @NonNull
    public static PowerDownloadTarget getEdgePageTarget() {
        return target(KEY_EDGE_PAGE_TARGET, DEFAULT_TARGET);
    }

    public static boolean isMenuEnabled() {
        return Settings.getBoolean(KEY_MENU_ENABLED, false);
    }

    @NonNull
    public static PowerDownloadTarget getMenuTarget() {
        return target(KEY_MENU_TARGET, DEFAULT_TARGET);
    }

    public static boolean isVolumeEnabled() {
        return Settings.getBoolean(KEY_VOLUME_ENABLED, false);
    }

    public static void putVolumeEnabled(boolean value) {
        Settings.putBoolean(KEY_VOLUME_ENABLED, value);
    }

    @NonNull
    public static PowerDownloadTarget getVolumeUpTarget() {
        return target(KEY_VOLUME_UP_TARGET, DEFAULT_VOLUME_UP_TARGET);
    }

    @NonNull
    public static PowerDownloadTarget getVolumeDownTarget() {
        return target(KEY_VOLUME_DOWN_TARGET, DEFAULT_VOLUME_DOWN_TARGET);
    }

    /** What the old "auto download to network storage" switch did: first page, to the share. */
    public static void adoptLegacyAutoDownload() {
        Settings.putBoolean(KEY_FIRST_PAGE_ENABLED, true);
        Settings.putString(KEY_EDGE_PAGE_TARGET, PowerDownloadTarget.NETWORK_STORAGE.value);
    }

    /**
     * Network storage was switched off: every rule aimed at it goes off too, rather than staying
     * on and silently doing nothing. A volume key aimed at it stops being used, and with neither
     * key left the volume rule is off; volume-key page turning does not come back by itself.
     */
    public static void turnOffNetworkStorageRules() {
        PowerDownloadTarget share = PowerDownloadTarget.NETWORK_STORAGE;
        if (getPagesTarget() == share) {
            Settings.putBoolean(KEY_PAGES_ENABLED, false);
        }
        if (getEdgePageTarget() == share) {
            Settings.putBoolean(KEY_FIRST_PAGE_ENABLED, false);
            Settings.putBoolean(KEY_LAST_PAGE_ENABLED, false);
        }
        if (getMenuTarget() == share) {
            Settings.putBoolean(KEY_MENU_ENABLED, false);
        }
        boolean keyDropped = false;
        if (getVolumeUpTarget() == share) {
            Settings.putString(KEY_VOLUME_UP_TARGET, PowerDownloadTarget.NONE.value);
            keyDropped = true;
        }
        if (getVolumeDownTarget() == share) {
            Settings.putString(KEY_VOLUME_DOWN_TARGET, PowerDownloadTarget.NONE.value);
            keyDropped = true;
        }
        if (keyDropped && getVolumeUpTarget() == PowerDownloadTarget.NONE
                && getVolumeDownTarget() == PowerDownloadTarget.NONE) {
            putVolumeEnabled(false);
        }
    }

    @NonNull
    private static PowerDownloadTarget target(@NonNull String key,
                                              @NonNull PowerDownloadTarget fallback) {
        return PowerDownloadTarget.fromValue(Settings.getString(key, fallback.value), fallback);
    }
}
