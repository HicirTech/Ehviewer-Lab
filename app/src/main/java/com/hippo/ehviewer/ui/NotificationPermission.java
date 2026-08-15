/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;

/**
 * POST_NOTIFICATIONS is a runtime permission since Android 13; undeclared-at-runtime means every
 * download notification is silently dropped (#103). Asked once, on the first download start that
 * has an Activity to ask from. Denial never blocks the download — the foreground service runs
 * without its notification — but it is said out loud once.
 */
public final class NotificationPermission {

    private static final int REQUEST_CODE = 1013;

    private static boolean sHintShown;

    private NotificationPermission() {}

    /** Call where a download is about to start. No-op once granted, or without an Activity. */
    public static void onDownloadStart(@Nullable Context context) {
        if (Build.VERSION.SDK_INT < 33 || context == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!Settings.getNotificationPermissionRequested()) {
            Activity activity = unwrap(context);
            if (activity != null) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
                Settings.putNotificationPermissionRequested(true);
            }
            return;
        }
        // Asked before and still denied: downloads run invisibly — say so once per process.
        if (!sHintShown) {
            sHintShown = true;
            Toast.makeText(context.getApplicationContext(),
                    R.string.notifications_disabled_hint, Toast.LENGTH_LONG).show();
        }
    }

    @Nullable
    private static Activity unwrap(@Nullable Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
