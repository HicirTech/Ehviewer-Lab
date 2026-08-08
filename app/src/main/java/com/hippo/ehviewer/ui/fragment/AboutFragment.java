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

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.updater.AppUpdater;
import com.hippo.util.AppHelper;
import com.hippo.util.ExceptionUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

//import com.microsoft.appcenter.distribute.Distribute;

public class AboutFragment extends BasePreferenceFragmentCompat
        implements Preference.OnPreferenceClickListener {

    private static final String KEY_AUTHOR = "author";
    private static final String KEY_CHECK_FOR_UPDATES = "check_for_updates";

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.about_settings, null);


        Preference checkForUpdate = findPreference(KEY_CHECK_FOR_UPDATES);
        if (checkForUpdate != null) {
            checkForUpdate.setOnPreferenceClickListener(this);
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        String key = preference.getKey();
        Activity activity =getActivity();
        if (KEY_CHECK_FOR_UPDATES.equals(key)&&activity!=null) {
//            Settings.setCheckUpdate(false);
//            Distribute.checkForUpdate();
            AppUpdater.update(activity,true);
        }
        return true;
    }

//    private void openUrl(String url) {
//        Intent intent = new Intent(Intent.ACTION_VIEW);
//        intent.setData(Uri.parse(url));
//        Intent chooser = Intent.createChooser(intent, "");
//        startActivity(chooser);
//    }
}
