/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.preference;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.storage.ConnectionDraft;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.ehviewer.storage.SelfCheck;
import com.hippo.preference.DialogPreference;
import com.hippo.util.IoThreadPoolExecutor;
import com.hippo.lib.yorozuya.SimpleHandler;

/**
 * The whole SMB connection as one entity (#133): a single row whose summary says where it points
 * and what the last save's probe found, and one dialog that edits every field. Saving runs the
 * connect / read / write check as part of the save — pass and the dialog closes, fail and it
 * stays open with the result inline. Nothing persists until the check passes.
 */
public class SmbConnectionPreference extends DialogPreference {

    @Nullable private EditText mHost;
    @Nullable private EditText mPort;
    @Nullable private EditText mShareName;
    @Nullable private EditText mSharePath;
    @Nullable private EditText mUsername;
    @Nullable private EditText mPassword;
    @Nullable private CheckBox mSigning;
    @Nullable private TextView mResult;

    private boolean mChecking;
    /** Armed after a readable-but-not-writable result; the next save accepts read-only. */
    private boolean mAcceptReadOnly;
    @Nullable private AlertDialog mShownDialog;

    public SmbConnectionPreference(Context context) {
        super(context);
        init();
    }

    public SmbConnectionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SmbConnectionPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setDialogLayoutResource(R.layout.preference_dialog_smb_connection);
        setPositiveButtonText(R.string.settings_storage_save);
        setNegativeButtonText(android.R.string.cancel);
        updateSummary();
    }

    @Override
    protected boolean needInputMethod() {
        return true;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        mHost = view.findViewById(R.id.smb_host);
        mPort = view.findViewById(R.id.smb_port);
        mShareName = view.findViewById(R.id.smb_share_name);
        mSharePath = view.findViewById(R.id.smb_share_path);
        mUsername = view.findViewById(R.id.smb_username);
        mPassword = view.findViewById(R.id.smb_password);
        mSigning = view.findViewById(R.id.smb_signing_disabled);
        mResult = view.findViewById(R.id.check_result);
        set(mHost, Settings.getSmbHost());
        set(mPort, Settings.getSmbPort());
        set(mShareName, Settings.getSmbShareName());
        set(mSharePath, Settings.getSmbSharePath());
        set(mUsername, Settings.getSmbUsername());
        set(mPassword, Settings.getSmbPassword());
        if (mSigning != null) {
            mSigning.setChecked(Settings.getSmbSigningDisabled());
        }
        mChecking = false;
        mAcceptReadOnly = false;
    }

    private static void set(@Nullable EditText field, @NonNull String value) {
        if (field != null) {
            field.setText(value);
        }
    }

    @NonNull
    private static String text(@Nullable EditText field) {
        return field == null || field.getText() == null ? "" : field.getText().toString();
    }

    /** Saving is the check: take over the positive button so failure keeps the dialog open. */
    @Override
    protected void onDialogCreated(AlertDialog dialog) {
        mShownDialog = dialog;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save(dialog));
        // A read-only acceptance answers one exact draft; editing anything revokes it (#140).
        android.text.TextWatcher disarm = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override public void afterTextChanged(android.text.Editable s) {
                disarmReadOnly();
            }
        };
        for (EditText field : new EditText[]{mHost, mPort, mShareName, mSharePath,
                mUsername, mPassword}) {
            if (field != null) {
                field.addTextChangedListener(disarm);
            }
        }
        if (mSigning != null) {
            mSigning.setOnCheckedChangeListener((v, checked) -> disarmReadOnly());
        }
    }

    private void disarmReadOnly() {
        if (!mAcceptReadOnly) {
            return;
        }
        mAcceptReadOnly = false;
        if (mShownDialog != null && mShownDialog.isShowing()) {
            mShownDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setText(R.string.settings_storage_save);
        }
    }

    private void save(@NonNull AlertDialog dialog) {
        if (mChecking) {
            return;
        }
        mChecking = true;
        final ConnectionDraft draft = new ConnectionDraft(
                text(mHost).trim(), text(mPort).trim(),
                text(mShareName).trim(), text(mSharePath).trim(),
                text(mUsername), text(mPassword),
                mSigning != null && mSigning.isChecked());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        if (mResult != null) {
            mResult.setVisibility(View.VISIBLE);
            mResult.setText(R.string.settings_storage_checking);
        }
        IoThreadPoolExecutor.Companion.getInstance().execute(() -> {
            final SelfCheck result = NetworkStorage.active().selfCheck(draft);
            SimpleHandler.getInstance().post(() -> onChecked(dialog, draft, result));
        });
    }

    private void onChecked(@NonNull AlertDialog dialog, @NonNull ConnectionDraft draft,
                           @NonNull SelfCheck result) {
        mChecking = false;
        if (!dialog.isShowing()) {
            return;
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        if (result.allOk()) {
            commit(draft, true);
            dialog.dismiss();
            return;
        }
        if (result.readOnly() && mAcceptReadOnly) {
            commit(draft, false);
            dialog.dismiss();
            return;
        }
        if (mResult != null) {
            StringBuilder sb = new StringBuilder(stagesText(result));
            if (result.readOnly()) {
                sb.append('\n').append(getContext().getString(
                        R.string.settings_storage_readonly_question));
                // The next tap of the same button is the answer.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setText(R.string.settings_storage_readonly_save);
                mAcceptReadOnly = true;
            }
            mResult.setText(sb);
        }
    }

    /** Stage words only — the app's copy carries no symbols. */
    @NonNull
    private String stagesText(@NonNull SelfCheck r) {
        Context c = getContext();
        String pass = c.getString(R.string.settings_storage_stage_pass);
        String fail = c.getString(R.string.settings_storage_stage_fail);
        String skip = c.getString(R.string.settings_storage_stage_skipped);
        StringBuilder sb = new StringBuilder();
        sb.append(c.getString(R.string.settings_storage_check_connect)).append(' ')
                .append(r.connectOk ? pass : fail).append('\n');
        sb.append(c.getString(R.string.settings_storage_check_read)).append(' ')
                .append(!r.connectOk ? skip : r.readOk ? pass : fail).append('\n');
        sb.append(c.getString(R.string.settings_storage_check_write)).append(' ')
                .append(!r.readOk ? skip : r.writeOk ? pass : fail);
        if (r.failure != null) {
            sb.append('\n').append(r.failure);
        }
        return sb.toString();
    }

    /** The one place the connection reaches the live configuration — as a whole. */
    private void commit(@NonNull ConnectionDraft draft, boolean writable) {
        androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(getContext())
                .edit()
                .putString(Settings.KEY_SMB_HOST, draft.host)
                .putString(Settings.KEY_SMB_PORT, draft.port)
                .putString(Settings.KEY_SMB_SHARE_NAME, draft.shareName)
                .putString(Settings.KEY_SMB_SHARE_PATH, draft.sharePath)
                .putString(Settings.KEY_SMB_USERNAME, draft.username)
                .putString(Settings.KEY_SMB_PASSWORD, draft.password)
                .putBoolean(Settings.KEY_SMB_SIGNING_DISABLED, draft.signingDisabled)
                .putString(Settings.KEY_STORAGE_LAST_CHECK,
                        writable ? Settings.LAST_CHECK_READ_WRITE : Settings.LAST_CHECK_READ_ONLY)
                .apply();
        updateSummary();
    }

    /** Protocol, address, and what the last save's probe established — words, no symbols. */
    public void updateSummary() {
        Context c = getContext();
        if (TextUtils.isEmpty(Settings.getSmbHost())
                || TextUtils.isEmpty(Settings.getSmbShareName())) {
            setSummary(c.getString(R.string.settings_storage_connection_unconfigured));
            return;
        }
        String address = NetworkStorage.active().address();
        String access;
        switch (Settings.getStorageLastCheck()) {
            case Settings.LAST_CHECK_READ_WRITE:
                access = c.getString(R.string.settings_storage_access_read_write);
                break;
            case Settings.LAST_CHECK_READ_ONLY:
                access = c.getString(R.string.settings_storage_access_read_only);
                break;
            default:
                access = c.getString(R.string.settings_storage_access_untested);
                break;
        }
        setSummary(address + "\n"
                + NetworkStorage.active().displayName() + " " + access);
    }
}
