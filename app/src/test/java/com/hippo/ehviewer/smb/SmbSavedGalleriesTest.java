/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.storage.DownloadState;
import com.hippo.ehviewer.storage.GalleryRef;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The badge oracle (#144): saved = folders in download/ minus every claim in state/, disabled
 * answers nothing, and a failed read keeps the previous answer instead of blanking screens.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbSavedGalleriesTest.ShadowSmbInventory.class,
                   SmbSavedGalleriesTest.ShadowSmbDownloadStateStore.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class SmbSavedGalleriesTest {

    static final List<GalleryRef> folders = new ArrayList<>();
    static final List<DownloadState.Published> claims = new ArrayList<>();
    static boolean readFails;

    @Implements(SmbInventory.class)
    public static class ShadowSmbInventory {
        @Implementation
        protected static List<GalleryRef> listGalleryRefs() {
            if (readFails) {
                throw new IllegalStateException("share unreachable");
            }
            return new ArrayList<>(folders);
        }
    }

    @Implements(SmbDownloadStateStore.class)
    public static class ShadowSmbDownloadStateStore {
        @Implementation
        protected static List<DownloadState.Published> readAll() {
            return new ArrayList<>(claims);
        }
    }

    @Before
    public void setUp() throws Exception {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(Settings.KEY_SMB_SHARE_NAME, "share");
        Settings.putBoolean(Settings.KEY_NETWORK_STORAGE_ENABLED, true);
        folders.clear();
        claims.clear();
        readFails = false;
        // Robolectric's clock starts near zero, which the TTL check reads as "just refreshed";
        // a minute on the clock makes loadedAt=0 mean "never" the way it does in production.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(2));
        reset(Collections.emptySet());
    }

    /** The instance is a process-wide singleton; every test starts from a cold, empty answer. */
    private static void reset(Set<Long> saved) throws Exception {
        SmbSavedGalleries instance = SmbSavedGalleries.getInstance();
        Field savedField = SmbSavedGalleries.class.getDeclaredField("saved");
        savedField.setAccessible(true);
        savedField.set(instance, saved);
        Field loadedField = SmbSavedGalleries.class.getDeclaredField("loadedAt");
        loadedField.setAccessible(true);
        loadedField.set(instance, 0L);
    }

    private static DownloadState.Published claimOf(long gid) {
        DownloadState.Task task = new DownloadState.Task(gid, null, "claimed", 0, 0, 1L, null);
        return new DownloadState.Published(new DownloadState.ClientState(
                "client-b", "OtherDevice", Collections.singletonList(task)), true, 1L);
    }

    /** The core subtraction: a folder is only "saved" while nobody claims it — dead or alive. */
    @Test
    public void aClaimedFolderIsNotSaved() {
        folders.add(new GalleryRef("42-Answer", 1L));
        folders.add(new GalleryRef("43-Other", 1L));
        claims.add(claimOf(43L));

        refreshUntil(() -> SmbSavedGalleries.getInstance().contains(42L));

        assertTrue(SmbSavedGalleries.getInstance().contains(42L));
        assertFalse("a claimed gallery must not badge as saved",
                SmbSavedGalleries.getInstance().contains(43L));
    }

    /** Turning the switch off empties every screen at once, cache or no cache. */
    @Test
    public void disablingAnswersNothingImmediately() throws Exception {
        reset(Collections.singleton(42L));
        assertTrue(SmbSavedGalleries.getInstance().contains(42L));

        Settings.putBoolean(Settings.KEY_NETWORK_STORAGE_ENABLED, false);

        assertFalse(SmbSavedGalleries.getInstance().contains(42L));
    }

    /** A failed read keeps the previous answer — badges must not blank on a network hiccup. */
    @Test
    public void aFailedReadKeepsThePreviousAnswer() throws Exception {
        reset(Collections.singleton(42L));
        readFails = true;

        SmbSavedGalleries.getInstance().invalidate();
        SmbSavedGalleries.getInstance().refresh();
        sleepQuietly(300);

        assertTrue("the stale answer beats no answer",
                SmbSavedGalleries.getInstance().contains(42L));
    }

    private static void refreshUntil(java.util.function.BooleanSupplier done) {
        SmbSavedGalleries.getInstance().invalidate();
        SmbSavedGalleries.getInstance().refresh();
        long deadline = System.currentTimeMillis() + 5_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            sleepQuietly(20);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
