/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.homelab.HomelabSettings;

/** The homelab page (#207): one password for every homelab service, and super-resolution while reading. */
public class HomelabSettingsFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.homelab_settings);
        Preference password = findPreference(HomelabSettings.KEY_PASSWORD);
        Preference url = findPreference(HomelabSettings.KEY_SR_URL);
        if (password != null) {
            password.setOnPreferenceChangeListener(this);
            showPassword(password, HomelabSettings.getPassword());
        }
        if (url != null) {
            url.setOnPreferenceChangeListener(this);
            showUrl(url, Settings.getString(HomelabSettings.KEY_SR_URL, ""));
        }
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        String value = newValue == null ? "" : newValue.toString();
        if (HomelabSettings.KEY_SR_URL.equals(preference.getKey())) {
            if (!value.trim().isEmpty() && !HomelabSettings.isAllowedUrl(value)) {
                Toast.makeText(requireContext(), R.string.settings_homelab_sr_url_invalid, Toast.LENGTH_SHORT).show();
                return false;
            }
            showUrl(preference, value);
        } else {
            showPassword(preference, value);
        }
        return true;
    }

    private static void showUrl(@NonNull Preference preference, @NonNull String url) {
        String shown = url.trim();
        if (shown.isEmpty()) {
            preference.setSummary(R.string.settings_homelab_sr_url_unset);
        } else {
            preference.setSummary(shown);
        }
    }

    /** Says whether it is set, never what it is. */
    private static void showPassword(@NonNull Preference preference, @NonNull String password) {
        preference.setSummary(password.isEmpty()
                ? R.string.settings_homelab_password_unset : R.string.settings_homelab_password_set);
    }
}
