/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.download.part;

import android.content.res.Resources;
import android.view.View;

import androidx.annotation.Nullable;

import com.hippo.ehviewer.R;
import com.hippo.widget.FabLayout;
import com.sxj.paginationlib.PaginationIndicator;

/** Keeps the download list's FABs above its pagination bar (#74). */
public final class FabPaginationClearance {

    private FabPaginationClearance() {}

    /** Measured height: layout weight settles the bar short of its declared 40dp. */
    public static void update(@Nullable FabLayout fabLayout, @Nullable PaginationIndicator indicator) {
        if (fabLayout == null) {
            return;
        }
        Resources resources = fabLayout.getResources();
        int margin = resources.getDimensionPixelOffset(R.dimen.corner_fab_margin);
        int clearance = 0;
        if (indicator != null && indicator.getVisibility() == View.VISIBLE) {
            int measured = indicator.getHeight();
            clearance = measured > 0
                    ? measured
                    : resources.getDimensionPixelOffset(R.dimen.download_pagination_height);
            if (measured <= 0) {
                // Not laid out yet: re-measure once it is.
                indicator.post(() -> update(fabLayout, indicator));
            }
        }
        fabLayout.setPadding(fabLayout.getPaddingLeft(), fabLayout.getPaddingTop(),
                margin, margin + clearance);
    }
}
