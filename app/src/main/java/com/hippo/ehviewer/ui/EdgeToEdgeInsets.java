/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.hippo.lib.yorozuya.ResourcesUtils;

/** Replaces the drawer's fitsSystemWindows; ContentLayout consumes the bottom inset (#32). */
public final class EdgeToEdgeInsets {

    private EdgeToEdgeInsets() {}

    public static void apply(@NonNull Activity activity, @NonNull View drawer) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        final View contentRoot = activity.findViewById(android.R.id.content);
        // Stands in for the status bar scrim the drawer no longer draws.
        contentRoot.setBackgroundColor(
                ResourcesUtils.getAttrColor(activity, androidx.appcompat.R.attr.colorPrimaryDark));
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // EhDrawerLayout ignores padding set on itself.
            contentRoot.setPadding(bars.left, bars.top, bars.right, 0);
            return new WindowInsetsCompat.Builder(insets)
                    .setInsets(WindowInsetsCompat.Type.systemBars(),
                            Insets.of(0, 0, 0, bars.bottom))
                    .build();
        });
    }
}
