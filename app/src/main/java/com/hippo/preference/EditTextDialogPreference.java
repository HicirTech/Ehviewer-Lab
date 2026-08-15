/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.preference;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;

/**
 * A single-string {@link DialogPreference}, the family's answer to androidx EditTextPreference:
 * one text box in an app-styled dialog, persisted on OK through callChangeListener.
 */
public class EditTextDialogPreference extends DialogPreference {

    private int mInputType = InputType.TYPE_CLASS_TEXT;
    @Nullable
    private EditText mEditText;

    public EditTextDialogPreference(Context context) {
        super(context);
        init(context, null);
    }

    public EditTextDialogPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public EditTextDialogPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, @Nullable AttributeSet attrs) {
        if (attrs != null) {
            android.content.res.TypedArray a = context.obtainStyledAttributes(
                    attrs, new int[]{android.R.attr.inputType});
            mInputType = a.getInt(0, InputType.TYPE_CLASS_TEXT);
            a.recycle();
        }
        setDialogLayoutResource(R.layout.preference_dialog_edit_text);
        setPositiveButtonText(android.R.string.ok);
        setNegativeButtonText(android.R.string.cancel);
    }

    @Nullable
    private String mText;
    private boolean mTextSet;

    @Nullable
    public String getText() {
        return mTextSet ? mText : getPersistedString(null);
    }

    /**
     * Sets the value: persisted when the preference is persistent, held on the object when it
     * is not (the settings page's draft mode, #133). The auto-tuner also applies through this.
     */
    public void setText(@Nullable String text) {
        mText = text;
        mTextSet = true;
        if (isPersistent()) {
            persistString(text);
        }
        notifyChanged();
    }

    @Override
    protected void onSetInitialValue(@Nullable Object defaultValue) {
        setText(getPersistedString(defaultValue == null ? null : String.valueOf(defaultValue)));
    }

    @Override
    protected boolean needInputMethod() {
        return true;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
        EditText editText = view.findViewById(R.id.edit_text);
        editText.setInputType(mInputType);
        String current = getText();
        editText.setText(current);
        if (current != null) {
            editText.setSelection(current.length());
        }
        mEditText = editText;
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        EditText editText = mEditText;
        mEditText = null;
        if (!positiveResult || editText == null) {
            return;
        }
        String value = editText.getText().toString();
        // The listener may veto and apply an adjusted value itself (concurrency clamping).
        if (callChangeListener(value)) {
            setText(value);
        }
    }
}
