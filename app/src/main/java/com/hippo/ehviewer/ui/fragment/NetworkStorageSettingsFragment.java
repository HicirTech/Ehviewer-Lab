package com.hippo.ehviewer.ui.fragment;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.preference.SmbConnectionPreference;
import com.hippo.preference.EditTextDialogPreference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.download.PowerDownloadSettings;
import com.hippo.ehviewer.smb.SmbBenchmark;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.HashMap;
import java.util.Map;

public class NetworkStorageSettingsFragment extends BasePreferenceFragmentCompat implements Preference.OnPreferenceChangeListener {

    @Nullable
    private TwoStatePreference mMasterSwitch;
    @Nullable
    private com.hippo.preference.ListPreference mProtocol;
    @Nullable
    private SmbConnectionPreference mConnection;
    @Nullable
    private EditTextDialogPreference mDeviceName;
    private Preference mBenchmark;
    private Preference mAutoTune;
    private EditTextDialogPreference mMetadataConcurrency;
    private EditTextDialogPreference mImageConcurrency;

    private final Map<Preference, CharSequence> mHintSummaries = new HashMap<>();

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.network_storage_settings);

        mMasterSwitch = findPreference(Settings.KEY_NETWORK_STORAGE_ENABLED);
        mProtocol = findPreference(Settings.KEY_STORAGE_PROTOCOL);
        mConnection = findPreference("smb_connection");
        mDeviceName = findPreference(Settings.KEY_SMB_DEVICE_NAME);
        mBenchmark = findPreference("smb_benchmark");
        mAutoTune = findPreference("smb_auto_tune");
        mMetadataConcurrency = findPreference(Settings.KEY_SMB_METADATA_CONCURRENCY);
        mImageConcurrency = findPreference(Settings.KEY_SMB_IMAGE_CONCURRENCY);

        for (EditTextDialogPreference pref : new EditTextDialogPreference[]{
                mMetadataConcurrency, mImageConcurrency}) {
            if (pref != null) {
                pref.setOnPreferenceChangeListener(this);
            }
        }
        updateConcurrencySummaries();

        if (mMasterSwitch != null) {
            mMasterSwitch.setOnPreferenceChangeListener(this);
        }
        if (mProtocol != null) {
            mProtocol.setOnPreferenceChangeListener(this);
        }
        applyProtocol(Settings.getStorageProtocol());
        if (mDeviceName != null) {
            cacheHint(mDeviceName, null);
            mDeviceName.setOnPreferenceChangeListener(this);
            // Resolved, so an unset field shows the published model name.
            updateTextSummary(mDeviceName, Settings.getSmbDeviceName());
        }
        if (mBenchmark != null) {
            mBenchmark.setOnPreferenceClickListener(preference -> {
                runBenchmark();
                return true;
            });
        }
        if (mAutoTune != null) {
            mAutoTune.setOnPreferenceClickListener(preference -> {
                runAutoTune();
                return true;
            });
        }

        applyMasterState(Settings.getNetworkStorageEnabled());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String value = newValue == null ? "" : String.valueOf(newValue);
        if (preference == mMetadataConcurrency || preference == mImageConcurrency) {
            // Typed input clamps to the nearest bound: 999 means "a lot", not the default.
            int parsed;
            try {
                parsed = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            int clamped = Math.max(com.hippo.ehviewer.smb.SmbConcurrency.MIN,
                    Math.min(com.hippo.ehviewer.smb.SmbConcurrency.MAX, parsed));
            ((EditTextDialogPreference) preference).setText(String.valueOf(clamped));
            updateConcurrencySummaries();
            return false;   // we stored the clamped value ourselves
        }
        if (preference == mMasterSwitch) {
            boolean enabled = Boolean.TRUE.equals(newValue);
            if (!enabled) {
                PowerDownloadSettings.turnOffNetworkStorageRules();
            }
            applyMasterState(enabled);
            // Posted: the new value is persisted only after this listener returns true.
            com.hippo.lib.yorozuya.SimpleHandler.getInstance().post(() ->
                    com.hippo.ehviewer.smb.SmbDirectDownloader.getInstance()
                            .onSmbAvailabilityChanged());
            return true;
        }
        if (preference == mProtocol) {
            applyProtocol(value);
            return true;
        }
        if (preference == mDeviceName) {
            // Not Settings.getSmbDeviceName(): it still holds the old value here.
            String model = android.os.Build.MODEL;
            updateTextSummary(mDeviceName, value.trim().isEmpty()
                    ? (model == null || model.trim().isEmpty() ? "Android" : model.trim())
                    : value);
        }
        return true;
    }

    private void applyProtocol(@NonNull String protocol) {
        // Empty means never configured; the selector then shows its SMB default.
        boolean smb = protocol.isEmpty() || NetworkStorage.PROTOCOL_SMB.equals(protocol);
        for (Preference row : new Preference[]{mConnection, mBenchmark}) {
            if (row != null) {
                row.setVisible(smb);
            }
        }
    }

    private void applyMasterState(boolean enabled) {
        if (mProtocol != null) mProtocol.setEnabled(enabled);
        if (mConnection != null) mConnection.setEnabled(enabled);
        if (mDeviceName != null) mDeviceName.setEnabled(enabled);
        if (mBenchmark != null) mBenchmark.setEnabled(enabled);
        if (mAutoTune != null) mAutoTune.setEnabled(enabled);
        if (mMetadataConcurrency != null) mMetadataConcurrency.setEnabled(enabled);
        if (mImageConcurrency != null) mImageConcurrency.setEnabled(enabled);
    }

    private void updateTextSummary(@Nullable EditTextDialogPreference preference, @Nullable String value) {
        if (preference == null) {
            return;
        }
        if (value == null || value.trim().isEmpty()) {
            preference.setSummary(mHintSummaries.get(preference));
        } else {
            preference.setSummary(value);
        }
    }

    private void cacheHint(@NonNull Preference pref, @Nullable CharSequence fallback) {
        if (mHintSummaries.containsKey(pref)) {
            return;
        }
        CharSequence current = pref.getSummary();
        if (current == null || current.length() == 0) {
            current = fallback;
        }
        mHintSummaries.put(pref, current);
    }

    private void updateConcurrencySummaries() {
        if (mMetadataConcurrency != null) {
            mMetadataConcurrency.setSummary(getString(
                    R.string.settings_smb_metadata_concurrency_summary,
                    String.valueOf(com.hippo.ehviewer.smb.SmbConcurrency.metadata())));
        }
        if (mImageConcurrency != null) {
            mImageConcurrency.setSummary(getString(
                    R.string.settings_smb_image_concurrency_summary,
                    String.valueOf(com.hippo.ehviewer.smb.SmbConcurrency.image())));
        }
    }

    private void runAutoTune() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final CharSequence idleSummary = mAutoTune == null ? null : mAutoTune.getSummary();
        if (mAutoTune != null) {
            mAutoTune.setEnabled(false);
        }
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            final com.hippo.ehviewer.smb.SmbAutoTune.Result result =
                    com.hippo.ehviewer.smb.SmbAutoTune.run((stage, conc) ->
                            SimpleHandler.getInstance().post(() -> {
                                // appContext: a detached fragment's getString throws.
                                if (mAutoTune != null) {
                                    if ("collect".equals(stage)) {
                                        mAutoTune.setSummary(appContext.getString(
                                                R.string.settings_smb_autotune_collecting));
                                    } else {
                                        mAutoTune.setSummary(appContext.getString(
                                                R.string.settings_smb_autotune_running,
                                                "metadata".equals(stage)
                                                        ? appContext.getString(R.string.settings_smb_autotune_stage_metadata)
                                                        : appContext.getString(R.string.settings_smb_autotune_stage_image),
                                                conc));
                                    }
                                }
                            }));
            SimpleHandler.getInstance().post(() -> {
                // Saved before the isAdded check: minutes of measurement outlive the screen.
                if (result.ok) {
                    Settings.putString(Settings.KEY_SMB_METADATA_CONCURRENCY,
                            String.valueOf(result.bestMetadata));
                    Settings.putString(Settings.KEY_SMB_IMAGE_CONCURRENCY,
                            String.valueOf(result.bestImage));
                }
                if (mAutoTune != null) {
                    mAutoTune.setEnabled(true);
                    mAutoTune.setSummary(idleSummary);
                }
                if (!isAdded() || getContext() == null) {
                    return;
                }
                if (!result.ok) {
                    new AlertDialog.Builder(requireContext())
                            .setTitle(R.string.settings_smb_autotune)
                            .setMessage("empty".equals(result.problem)
                                    ? R.string.settings_smb_benchmark_empty
                                    : R.string.settings_smb_benchmark_unconfigured)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                if (mMetadataConcurrency != null) {
                    mMetadataConcurrency.setText(String.valueOf(result.bestMetadata));
                }
                if (mImageConcurrency != null) {
                    mImageConcurrency.setText(String.valueOf(result.bestImage));
                }
                updateConcurrencySummaries();
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.settings_smb_autotune)
                        .setMessage(describeTune(result))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    @NonNull
    private CharSequence describeTune(@NonNull com.hippo.ehviewer.smb.SmbAutoTune.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.settings_smb_autotune_applied,
                r.bestMetadata, r.bestImage)).append("\n\n");
        sb.append(getString(R.string.settings_smb_autotune_meta_header, r.galleries)).append('\n');
        for (java.util.Map.Entry<Integer, Long> e : r.metadataMillis.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append(" ms\n");
        }
        if (!r.imageMillis.isEmpty()) {
            sb.append('\n').append(getString(
                    R.string.settings_smb_autotune_image_header, r.imagesSampled)).append('\n');
            for (java.util.Map.Entry<Integer, Long> e : r.imageMillis.entrySet()) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append(" ms\n");
            }
        }
        return sb.toString();
    }

    private void runBenchmark() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final CharSequence idleSummary = mBenchmark == null ? null : mBenchmark.getSummary();
        if (mBenchmark != null) {
            mBenchmark.setEnabled(false);
            mBenchmark.setSummary(R.string.settings_smb_benchmark_running);
        }
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            final SmbBenchmark.Result result = SmbBenchmark.run();
            SimpleHandler.getInstance().post(() -> {
                if (mBenchmark != null) {
                    mBenchmark.setEnabled(true);
                    mBenchmark.setSummary(idleSummary);
                }
                if (!isAdded() || getContext() == null) {
                    return;
                }
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.settings_smb_benchmark_title)
                        .setMessage(describe(appContext, result))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        });
    }

    @NonNull
    private static CharSequence describe(@NonNull Context context,
                                         @NonNull SmbBenchmark.Result r) {
        if (!r.ok) {
            return "empty".equals(r.problem)
                    ? context.getString(R.string.settings_smb_benchmark_empty)
                    : context.getString(R.string.settings_smb_benchmark_unconfigured);
        }
        String perGallery = String.format(java.util.Locale.US, "%.1f", r.millisPerGallery());
        String throughput = String.format(java.util.Locale.US, "%.1f", r.imageMegabytesPerSecond());
        return context.getString(R.string.settings_smb_benchmark_result,
                r.galleriesOnShare, r.listMillis,
                r.metadataConcurrency, r.metadataRead, r.metadataMillis, perGallery,
                r.imageConcurrency, r.imagesRead, r.imageMillis, throughput)
                + "\n\n"
                + context.getString(R.string.settings_smb_benchmark_hint);
    }
}
