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

/** Android 13+ silently drops download notifications until POST_NOTIFICATIONS is granted. */
public final class NotificationPermission {

    private static final int REQUEST_CODE = 1013;

    private static boolean sHintShown;

    private NotificationPermission() {}

    /** Call where a download is about to start, from any thread. */
    public static void onDownloadStart(@Nullable Context context) {
        if (Build.VERSION.SDK_INT < 33 || context == null) {
            return;
        }
        // Off the main thread the toast kills the process.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            final Context c = context;
            com.hippo.lib.yorozuya.SimpleHandler.getInstance().post(() -> onDownloadStart(c));
            return;
        }
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!Settings.getNotificationPermissionRequested()) {
            Activity activity = unwrap(context);
            if (activity != null && (activity.isFinishing() || activity.isDestroyed())) {
                // A dead host must not spend the one-time ask.
                activity = null;
            }
            if (activity != null) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
                Settings.putNotificationPermissionRequested(true);
            }
            return;
        }
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
