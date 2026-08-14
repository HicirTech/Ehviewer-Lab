package com.hippo.ehviewer.ui.fragment;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.TwoStatePreference;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.smb.SmbBenchmark;
import com.hippo.ehviewer.smb.SmbConnection;
import com.hippo.lib.yorozuya.SimpleHandler;
import com.hippo.util.IoThreadPoolExecutor;

import java.util.HashMap;
import java.util.Map;

public class SmbSettingsFragment extends PreferenceFragmentCompat implements Preference.OnPreferenceChangeListener {

    @Nullable
    private TwoStatePreference mMasterSwitch;
    @Nullable
    private TwoStatePreference mAutoDownloadSwitch;
    @Nullable
    private EditTextPreference mHost;
    @Nullable
    private EditTextPreference mPort;
    @Nullable
    private EditTextPreference mShareName;
    @Nullable
    private EditTextPreference mSharePath;
    @Nullable
    private EditTextPreference mDeviceName;
    @Nullable
    private EditTextPreference mUsername;
    @Nullable
    private EditTextPreference mPassword;
    @Nullable
    private Preference mTestConnection;
    private Preference mBenchmark;
    private Preference mAutoTune;
    private EditTextPreference mMetadataConcurrency;
    private EditTextPreference mImageConcurrency;

    /**
     * Snapshot of each EditText preference's XML-defined {@code android:summary} taken before
     * we overwrite it with the entered value. When the user clears a field we restore this
     * original hint (e.g. "Example: 192.168.1.10") instead of showing a generic placeholder.
     */
    private final Map<Preference, CharSequence> mHintSummaries = new HashMap<>();

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        addPreferencesFromResource(R.xml.smb_settings);

        mMasterSwitch = findPreference(Settings.KEY_SMB_SAVE_ENABLED);
        mAutoDownloadSwitch = findPreference(Settings.KEY_SMB_AUTO_DOWNLOAD_ENABLED);
        mHost = findPreference(Settings.KEY_SMB_HOST);
        mPort = findPreference(Settings.KEY_SMB_PORT);
        mShareName = findPreference(Settings.KEY_SMB_SHARE_NAME);
        mSharePath = findPreference(Settings.KEY_SMB_SHARE_PATH);
        mDeviceName = findPreference(Settings.KEY_SMB_DEVICE_NAME);
        mUsername = findPreference(Settings.KEY_SMB_USERNAME);
        mPassword = findPreference(Settings.KEY_SMB_PASSWORD);
        mTestConnection = findPreference("smb_test_connection");
        mBenchmark = findPreference("smb_benchmark");
        mAutoTune = findPreference("smb_auto_tune");
        mMetadataConcurrency = findPreference(Settings.KEY_SMB_METADATA_CONCURRENCY);
        mImageConcurrency = findPreference(Settings.KEY_SMB_IMAGE_CONCURRENCY);

        // Plain number boxes, clamped on entry. A dropdown of blessed values was wrong twice
        // over: the blessed values came from one library size, and the tuner below can land on
        // any number in 1..64.
        for (EditTextPreference pref : new EditTextPreference[]{
                mMetadataConcurrency, mImageConcurrency}) {
            if (pref == null) {
                continue;
            }
            pref.setOnBindEditTextListener(editText ->
                    editText.setInputType(InputType.TYPE_CLASS_NUMBER));
            pref.setOnPreferenceChangeListener(this);
        }
        updateConcurrencySummaries();

        if (mPassword != null) {
            mPassword.setOnBindEditTextListener(editText -> editText.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        }

        if (mMasterSwitch != null) {
            mMasterSwitch.setOnPreferenceChangeListener(this);
        }
        if (mAutoDownloadSwitch != null) {
            mAutoDownloadSwitch.setOnPreferenceChangeListener(this);
        }
        if (mHost != null) {
            cacheHint(mHost, null);
            mHost.setOnPreferenceChangeListener(this);
            updateTextSummary(mHost, Settings.getSmbHost());
        }
        if (mPort != null) {
            cacheHint(mPort, null);
            mPort.setOnPreferenceChangeListener(this);
            updateTextSummary(mPort, Settings.getSmbPort());
        }
        if (mShareName != null) {
            cacheHint(mShareName, null);
            mShareName.setOnPreferenceChangeListener(this);
            updateTextSummary(mShareName, Settings.getSmbShareName());
        }
        if (mSharePath != null) {
            cacheHint(mSharePath, null);
            mSharePath.setOnPreferenceChangeListener(this);
            updateTextSummary(mSharePath, Settings.getSmbSharePath());
        }
        if (mDeviceName != null) {
            cacheHint(mDeviceName, null);
            mDeviceName.setOnPreferenceChangeListener(this);
            // Shows the resolved name, so an unset field displays the model that will actually be
            // published rather than looking empty.
            updateTextSummary(mDeviceName, Settings.getSmbDeviceName());
        }
        if (mUsername != null) {
            // Username has no XML summary — fall back to a generic "tap to set" hint.
            cacheHint(mUsername, getString(R.string.settings_smb_field_unset));
            mUsername.setOnPreferenceChangeListener(this);
            updateTextSummary(mUsername, Settings.getSmbUsername());
        }
        if (mPassword != null) {
            cacheHint(mPassword, getString(R.string.settings_smb_field_unset_password));
            mPassword.setOnPreferenceChangeListener(this);
            updatePasswordSummary(Settings.getSmbPassword());
        }

        if (mTestConnection != null) {
            mTestConnection.setOnPreferenceClickListener(preference -> {
                testConnection();
                return true;
            });
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

        applyMasterState(Settings.getSmbSaveEnabled());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String value = newValue == null ? "" : String.valueOf(newValue);
        if (preference == mMetadataConcurrency || preference == mImageConcurrency) {
            // Clamp on entry, and store what was actually accepted, so the summary never shows
            // a number the pools will silently refuse.
            // Typed input clamps to the nearest bound, unlike a corrupt stored value, which
            // falls back to the default: someone who types 999 means "a lot", and answering
            // with 6 would look like the box ignored them. Unparseable input changes nothing.
            int parsed;
            try {
                parsed = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return false;
            }
            int clamped = Math.max(com.hippo.ehviewer.smb.SmbConcurrency.MIN,
                    Math.min(com.hippo.ehviewer.smb.SmbConcurrency.MAX, parsed));
            ((EditTextPreference) preference).setText(String.valueOf(clamped));
            updateConcurrencySummaries();
            return false;   // we stored the clamped value ourselves
        }
        if (preference == mMasterSwitch) {
            boolean enabled = Boolean.TRUE.equals(newValue);
            // When the master switch turns off, also force auto-download off (writing to
            // SharedPreferences directly so the saved state survives a restart) and grey
            // out the dependent UI immediately.
            if (!enabled && mAutoDownloadSwitch != null && mAutoDownloadSwitch.isChecked()) {
                mAutoDownloadSwitch.setChecked(false);
            }
            applyMasterState(enabled);
            // Take effect now rather than the next time some screen happens to ask. Turning this
            // off with a download running used to hide it from the list while its pages kept
            // being written to the share.
            //
            // Posted, not called: this listener runs *before* the new value is persisted -- that
            // is what returning true authorises -- so asking Settings here answers with the value
            // being replaced. Which is precisely how this went out wrong the first time: the
            // switch read off and the download carried on.
            com.hippo.lib.yorozuya.SimpleHandler.getInstance().post(() ->
                    com.hippo.ehviewer.smb.SmbDirectDownloader.getInstance()
                            .onSmbAvailabilityChanged());
            return true;
        }
        if (preference == mAutoDownloadSwitch) {
            return true;
        }
        if (preference == mHost) {
            updateTextSummary(mHost, value);
        } else if (preference == mPort) {
            updateTextSummary(mPort, value);
        } else if (preference == mShareName) {
            updateTextSummary(mShareName, value);
        } else if (preference == mSharePath) {
            updateTextSummary(mSharePath, value);
        } else if (preference == mDeviceName) {
            // Clearing it falls back to the model name, so show that rather than the empty value.
            updateTextSummary(mDeviceName,
                    value.trim().isEmpty() ? Settings.getSmbDeviceName() : value);
        } else if (preference == mUsername) {
            updateTextSummary(mUsername, value);
        } else if (preference == mPassword) {
            updatePasswordSummary(value);
        }
        return true;
    }

    private void applyMasterState(boolean enabled) {
        if (mAutoDownloadSwitch != null) mAutoDownloadSwitch.setEnabled(enabled);
        if (mHost != null) mHost.setEnabled(enabled);
        if (mPort != null) mPort.setEnabled(enabled);
        if (mShareName != null) mShareName.setEnabled(enabled);
        if (mSharePath != null) mSharePath.setEnabled(enabled);
        if (mDeviceName != null) mDeviceName.setEnabled(enabled);
        if (mUsername != null) mUsername.setEnabled(enabled);
        if (mPassword != null) mPassword.setEnabled(enabled);
        if (mTestConnection != null) mTestConnection.setEnabled(enabled);
        if (mBenchmark != null) mBenchmark.setEnabled(enabled);
        if (mAutoTune != null) mAutoTune.setEnabled(enabled);
        if (mMetadataConcurrency != null) mMetadataConcurrency.setEnabled(enabled);
        if (mImageConcurrency != null) mImageConcurrency.setEnabled(enabled);
    }

    private void updateTextSummary(@Nullable EditTextPreference preference, @Nullable String value) {
        if (preference == null) {
            return;
        }
        if (value == null || value.trim().isEmpty()) {
            // Empty — restore the original XML-defined hint (e.g. "Example: 192.168.1.10",
            // "Default: 445", "Path on share like: /ehviewer/") so the user sees the actual
            // help text instead of a generic placeholder.
            preference.setSummary(mHintSummaries.get(preference));
        } else {
            preference.setSummary(value);
        }
    }

    private void updatePasswordSummary(@Nullable String password) {
        if (mPassword == null) {
            return;
        }
        if (password == null || password.isEmpty()) {
            mPassword.setSummary(mHintSummaries.get(mPassword));
        } else {
            mPassword.setSummary("******");
        }
    }

    /**
     * Snapshot the current {@code android:summary} so future "empty value" rebinds can restore
     * it. If the preference has no XML summary (e.g. username/password), use the supplied
     * generic fallback. Stored once per preference; subsequent calls are no-ops.
     */
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

    /**
     * Measures the share at the settings currently in force and shows what came back.
     *
     * <p>A dialog and not a toast, unlike the connection test beside it. The result is several
     * numbers whose point is being compared with the next run's, and a toast that has already
     * faded is no use for that — nor is it any use to somebody who left the screen while a slow
     * share was still answering.
     *
     * <p>The summary line doubles as the progress indicator: there is nothing else on this screen
     * that could show one, and a share that takes ten seconds to answer would otherwise look like
     * a button that does nothing.
     */
    /** The two number boxes show their current value, the way host and port beside them do. */
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

    /**
     * Sweeps 1–64 against the real share and applies the winners.
     *
     * <p>Progress goes to the row's own summary — the sweep takes tens of seconds on a big
     * library and a button that long-silently works is indistinguishable from one that is
     * broken. The result dialog shows the whole table, not just the verdict, so a user whose
     * share behaves oddly can see the shape and not merely the number.
     */
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
                                if (mAutoTune != null) {
                                    if ("collect".equals(stage)) {
                                        mAutoTune.setSummary(getString(
                                                R.string.settings_smb_autotune_collecting));
                                    } else {
                                        mAutoTune.setSummary(getString(
                                                R.string.settings_smb_autotune_running,
                                                "metadata".equals(stage)
                                                        ? getString(R.string.settings_smb_autotune_stage_metadata)
                                                        : getString(R.string.settings_smb_autotune_stage_image),
                                                conc));
                                    }
                                }
                            }));
            SimpleHandler.getInstance().post(() -> {
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
                // Apply, then let the boxes above reflect it immediately.
                Settings.putString(Settings.KEY_SMB_METADATA_CONCURRENCY,
                        String.valueOf(result.bestMetadata));
                Settings.putString(Settings.KEY_SMB_IMAGE_CONCURRENCY,
                        String.valueOf(result.bestImage));
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
                // The fragment may be gone by now; the numbers are not worth crashing over.
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
        // One decimal place. The run-to-run spread is larger than a tenth of a millisecond, so
        // more digits would only suggest a precision the measurement does not have.
        String perGallery = String.format(java.util.Locale.US, "%.1f", r.millisPerGallery());
        String throughput = String.format(java.util.Locale.US, "%.1f", r.imageMegabytesPerSecond());
        return context.getString(R.string.settings_smb_benchmark_result,
                r.galleriesOnShare, r.listMillis,
                r.metadataConcurrency, r.metadataRead, r.metadataMillis, perGallery,
                r.imageConcurrency, r.imagesRead, r.imageMillis, throughput)
                + "\n\n"
                + context.getString(R.string.settings_smb_benchmark_hint);
    }

    private void testConnection() {
        // Snapshot the application context before going off-thread. Using the fragment's
        // getActivity()/getContext() from the worker would NPE if the user navigates away
        // from the Settings screen while the SMB probe is still running.
        Context context = getContext();
        if (context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            CharSequence message;
            try {
                // Non-null means the share is reachable but something about the setup needs
                // saying — currently only that the gallery directory could not be created.
                String warning = SmbConnection.testConnection();
                message = warning == null
                        ? appContext.getString(R.string.settings_smb_test_success)
                        : appContext.getString(R.string.settings_smb_test_success) + "\n" + warning;
            } catch (Exception e) {
                message = appContext.getString(R.string.settings_smb_test_failed, e.getMessage());
            }
            final CharSequence toastText = message;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(appContext, toastText, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        });
    }
}

