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
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.download.PowerDownloadSettings;
import com.hippo.ehviewer.download.PowerDownloadTarget;
import com.hippo.ehviewer.smb.SmbDownloadBoard;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.ui.VolumeKeyModeDialog;
import com.hippo.preference.EditTextDialogPreference;
import com.hippo.preference.ListPreference;

/** Settings -> Power Download (#159), xml/power_download_settings.xml. */
public class PowerDownloadFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceChangeListener {

    private static final PowerDownloadTarget[] RULE_TARGETS = {
            PowerDownloadTarget.PHONE, PowerDownloadTarget.NETWORK_STORAGE,
    };
    private static final PowerDownloadTarget[] VOLUME_TARGETS = {
            PowerDownloadTarget.NONE, PowerDownloadTarget.PHONE, PowerDownloadTarget.NETWORK_STORAGE,
    };
    private static final String[] SWITCH_KEYS = {
            PowerDownloadSettings.KEY_PAGES_ENABLED,
            PowerDownloadSettings.KEY_FIRST_PAGE_ENABLED,
            PowerDownloadSettings.KEY_LAST_PAGE_ENABLED,
            PowerDownloadSettings.KEY_MENU_ENABLED,
            PowerDownloadSettings.KEY_VOLUME_ENABLED,
    };

    @Nullable
    private EditTextDialogPreference mPagesCount;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.power_download_settings);

        bindTargets(PowerDownloadSettings.KEY_PAGES_TARGET, PowerDownloadSettings.getPagesTarget(),
                RULE_TARGETS);
        bindTargets(PowerDownloadSettings.KEY_EDGE_PAGE_TARGET, PowerDownloadSettings.getEdgePageTarget(),
                RULE_TARGETS);
        bindTargets(PowerDownloadSettings.KEY_MENU_TARGET, PowerDownloadSettings.getMenuTarget(),
                RULE_TARGETS);
        bindTargets(PowerDownloadSettings.KEY_VOLUME_UP_TARGET, PowerDownloadSettings.getVolumeUpTarget(),
                VOLUME_TARGETS);
        bindTargets(PowerDownloadSettings.KEY_VOLUME_DOWN_TARGET, PowerDownloadSettings.getVolumeDownTarget(),
                VOLUME_TARGETS);
        for (String key : SWITCH_KEYS) {
            Preference preference = findPreference(key);
            if (preference != null) {
                preference.setOnPreferenceChangeListener(this);
            }
        }
        mPagesCount = findPreference(PowerDownloadSettings.KEY_PAGES_COUNT);
        if (mPagesCount != null) {
            mPagesCount.setOnPreferenceChangeListener(this);
            updatePagesCountSummary();
        }
    }

    /**
     * {@code current} is set explicitly: this ListPreference ignores its XML default, so a target
     * never chosen would otherwise show no summary and no checked entry.
     */
    private void bindTargets(@NonNull String key, @NonNull PowerDownloadTarget current,
                             @NonNull PowerDownloadTarget[] targets) {
        ListPreference preference = findPreference(key);
        if (preference == null) {
            return;
        }
        CharSequence[] entries = new CharSequence[targets.length];
        CharSequence[] values = new CharSequence[targets.length];
        for (int i = 0; i < targets.length; i++) {
            entries[i] = targets[i].label(requireContext());
            values[i] = targets[i].value;
        }
        preference.setEntries(entries);
        preference.setEntryValues(values);
        preference.setValue(current.value);
        preference.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(@NonNull Preference preference, Object newValue) {
        if (preference == mPagesCount) {
            // Stored as accepted, like the concurrency boxes: a summary never shows a number the
            // rule would not use. Unparseable input changes nothing.
            int parsed;
            try {
                parsed = Integer.parseInt(String.valueOf(newValue).trim());
            } catch (NumberFormatException e) {
                return false;
            }
            mPagesCount.setText(String.valueOf(Math.max(PowerDownloadSettings.MIN_PAGES_COUNT, parsed)));
            updatePagesCountSummary();
            return false;
        }
        if (preference instanceof ListPreference) {
            // A rule can be aimed at network storage only while network storage can take it.
            return !PowerDownloadTarget.NETWORK_STORAGE.value.equals(newValue) || networkStorageUsable();
        }
        if (!Boolean.TRUE.equals(newValue)) {
            return true;
        }
        String key = preference.getKey();
        if (PowerDownloadSettings.KEY_VOLUME_ENABLED.equals(key)) {
            if (Settings.getVolumePage()) {
                VolumeKeyModeDialog.confirm(requireContext(), true, () -> {
                    Settings.putVolumePage(false);
                    ((TwoStatePreference) preference).setChecked(true);
                }, null);
                return false;
            }
            return true;
        }
        return targetOf(key) != PowerDownloadTarget.NETWORK_STORAGE || networkStorageUsable();
    }

    @NonNull
    private static PowerDownloadTarget targetOf(@NonNull String switchKey) {
        switch (switchKey) {
            case PowerDownloadSettings.KEY_PAGES_ENABLED:
                return PowerDownloadSettings.getPagesTarget();
            case PowerDownloadSettings.KEY_MENU_ENABLED:
                return PowerDownloadSettings.getMenuTarget();
            default:
                return PowerDownloadSettings.getEdgePageTarget();
        }
    }

    /** Says why not when it is not, since the switch or choice the user tapped will not move. */
    private boolean networkStorageUsable() {
        if (SmbDownloadBoard.smbAvailable()) {
            return true;
        }
        Toast.makeText(requireContext(), getString(R.string.smb_save_not_configured,
                NetworkStorage.active().displayName()), Toast.LENGTH_SHORT).show();
        return false;
    }

    /** The bare number: the title already says what it counts, and "1 pages" needs no plural. */
    private void updatePagesCountSummary() {
        if (mPagesCount != null) {
            mPagesCount.setSummary(String.valueOf(PowerDownloadSettings.getPagesCount()));
        }
    }
}
