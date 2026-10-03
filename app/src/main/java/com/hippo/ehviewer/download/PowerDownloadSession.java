/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.download;

import android.os.Bundle;

import androidx.annotation.NonNull;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * One reading of one gallery as Power Download's automatic rules see it (#159): the pages that
 * have been on screen, and the targets the gallery has already been sent to. Lives as long as
 * the reader, including across a rotation.
 */
public final class PowerDownloadSession {

    private static final String KEY_VIEWED = "power_download_viewed";
    private static final String KEY_SENT = "power_download_sent";

    private final Set<Integer> mViewed = new HashSet<>();
    private final Set<PowerDownloadTarget> mSent = EnumSet.noneOf(PowerDownloadTarget.class);

    /**
     * Records the page now on screen and returns the targets a rule now asks for. Each target is
     * returned at most once per reading, however many rules point at it.
     *
     * @param size the page count, or 0 or less while it is not known yet
     */
    @NonNull
    public Set<PowerDownloadTarget> onPageShown(int index, int size) {
        Set<PowerDownloadTarget> due = EnumSet.noneOf(PowerDownloadTarget.class);
        if (index < 0) {
            return due;
        }
        mViewed.add(index);
        if (PowerDownloadSettings.isPagesEnabled()) {
            // A gallery shorter than N counts once every page has been seen.
            int needed = PowerDownloadSettings.getPagesCount();
            if (size > 0) {
                needed = Math.min(needed, size);
            }
            if (mViewed.size() >= needed) {
                send(PowerDownloadSettings.getPagesTarget(), due);
            }
        }
        if (PowerDownloadSettings.isFirstPageEnabled() && index == 0) {
            send(PowerDownloadSettings.getEdgePageTarget(), due);
        }
        if (PowerDownloadSettings.isLastPageEnabled() && size > 0 && index == size - 1) {
            send(PowerDownloadSettings.getEdgePageTarget(), due);
        }
        return due;
    }

    private void send(@NonNull PowerDownloadTarget target, @NonNull Set<PowerDownloadTarget> due) {
        if (target != PowerDownloadTarget.NONE && mSent.add(target)) {
            due.add(target);
        }
    }

    public void saveTo(@NonNull Bundle outState) {
        int[] viewed = new int[mViewed.size()];
        int i = 0;
        for (int index : mViewed) {
            viewed[i++] = index;
        }
        outState.putIntArray(KEY_VIEWED, viewed);
        String[] sent = new String[mSent.size()];
        i = 0;
        for (PowerDownloadTarget target : mSent) {
            sent[i++] = target.value;
        }
        outState.putStringArray(KEY_SENT, sent);
    }

    public void restoreFrom(@NonNull Bundle savedState) {
        int[] viewed = savedState.getIntArray(KEY_VIEWED);
        if (viewed != null) {
            for (int index : viewed) {
                mViewed.add(index);
            }
        }
        String[] sent = savedState.getStringArray(KEY_SENT);
        if (sent != null) {
            for (String value : sent) {
                PowerDownloadTarget target = PowerDownloadTarget.fromValue(value, PowerDownloadTarget.NONE);
                if (target != PowerDownloadTarget.NONE) {
                    mSent.add(target);
                }
            }
        }
    }
}
