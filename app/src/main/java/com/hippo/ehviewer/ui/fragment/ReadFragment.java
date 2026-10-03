/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.fragment;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.download.PowerDownloadSettings;
import com.hippo.ehviewer.ui.VolumeKeyModeDialog;
import com.hippo.ehviewer.util.ReadingRefreshRate;

public class ReadFragment extends BasePreferenceFragmentCompat {

    /**
     * 设置->阅读界面
     * xml/read_settings.xml
     * @param savedInstanceState
     */
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.read_settings);
        if (!ReadingRefreshRate.isSupported()) {
            Preference preference = findPreference(Settings.KEY_READING_REFRESH_RATE);
            if (preference != null) {
                getPreferenceScreen().removePreference(preference);
            }
        }
        TwoStatePreference volumePage = findPreference(Settings.KEY_VOLUME_PAGE);
        if (volumePage != null) {
            volumePage.setOnPreferenceChangeListener((preference, newValue) -> {
                // The volume keys either turn pages or download (#159).
                if (Boolean.TRUE.equals(newValue) && PowerDownloadSettings.isVolumeEnabled()) {
                    VolumeKeyModeDialog.confirm(requireContext(), false, () -> {
                        PowerDownloadSettings.putVolumeEnabled(false);
                        volumePage.setChecked(true);
                    }, null);
                    return false;
                }
                return true;
            });
        }
    }
}
