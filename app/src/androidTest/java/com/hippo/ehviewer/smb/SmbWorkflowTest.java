/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.contrib.RecyclerViewActions.actionOnItem;
import static androidx.test.espresso.contrib.RecyclerViewActions.scrollTo;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.Gravity;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.contrib.NavigationViewActions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.ui.MainActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.regex.Pattern;

/** Every SMB screen, against the share this device is configured for. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SmbWorkflowTest {

    /** Generous: a cold debug process pays for multidex. */
    private static final long LAUNCH_MS = 20_000;
    private static final long SHARE_MS = 30_000;

    private UiDevice mDevice;
    private String mPkg;
    private ActivityScenario<MainActivity> mScenario;

    /** Installs drop the MANAGE_EXTERNAL_STORAGE appop; before API 30 this fails harmlessly. */
    @org.junit.BeforeClass
    public static void grantAllFilesAccess() throws IOException {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(
                "appops set " + InstrumentationRegistry.getInstrumentation()
                        .getTargetContext().getPackageName()
                        + " MANAGE_EXTERNAL_STORAGE allow");
    }

    @Before
    public void setUp() {
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mPkg = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getPackageName();
    }

    @After
    public void tearDown() {
        if (mScenario != null) {
            mScenario.close();
            mScenario = null;
        }
    }

    @Test
    public void launchArrivesAtTheMainScene() {
        launchMain();
    }

    @Test
    public void inventoryListsTheShare() {
        launchMain();
        openInventory();
    }

    @Test
    public void galleryDetailOpensFromTheInventory() {
        launchMain();
        openFirstGalleryDetail();
    }

    @Test
    public void readerMaterialisesAPageOffTheShare() throws IOException {
        assumeShareConfigured();
        launchMain();
        openFirstGalleryDetail();
        mDevice.executeShellCommand("logcat -c");
        UiObject2 read = mDevice.findObject(By.res(mPkg, "read"));
        assertNotNull("detail scene has no READ button", read);
        read.click();
        String line = awaitLogLine(Pattern.compile("materialize|preview idx="), SHARE_MS);
        assertNotNull("reader opened but no page came off the share", line);
        mDevice.pressBack();
    }

    @Test
    public void downloadsSceneArrives() {
        launchMain();
        openFromDrawer(R.id.nav_downloads);
        // An empty list is GONE, which UI Automator cannot see, so the tip stands in for it.
        assertTrue("neither the download list nor its empty-state tip appeared",
                waitAny(8_000, By.res(mPkg, "recycler_view"), By.res(mPkg, "tip")));
    }

    @Test
    public void smbSettingsExposeTheShareConfiguration() {
        launchMain();
        openFromDrawer(R.id.nav_settings);
        assertTrue("Settings screen did not open",
                Boolean.TRUE.equals(mDevice.wait(
                        Until.hasObject(withTextOf(R.string.settings_storage)), 8_000)));
        onView(withId(androidx.preference.R.id.recycler_view))
                .perform(actionOnItem(
                        hasDescendant(withText(R.string.settings_storage)), click()));
        assertTrue("network storage settings screen did not open",
                Boolean.TRUE.equals(mDevice.wait(
                        Until.hasObject(withTextOf(R.string.settings_storage_connection)), 8_000)));
        onView(withId(androidx.preference.R.id.recycler_view))
                .perform(scrollTo(hasDescendant(withText(R.string.settings_storage_enable_save))));
    }

    private void launchMain() {
        mScenario = ActivityScenario.launch(MainActivity.class);
        // Polled: one up-front dismissal loses the race with the first scene's rendering.
        long end = android.os.SystemClock.uptimeMillis() + LAUNCH_MS;
        while (android.os.SystemClock.uptimeMillis() < end) {
            if (mDevice.hasObject(By.res(mPkg, "search_bar"))) {
                dismissOneTimeOverlays();
                return;
            }
            dismissOneTimeOverlays();
            android.os.SystemClock.sleep(250);
        }
        throw new AssertionError("main scene did not draw its search bar");
    }

    private void openFromDrawer(int menuId) {
        mScenario.onActivity(a -> a.openDrawer(Gravity.LEFT));
        // Espresso cannot see the drawer's own scroller, so navigateTo may fire mid-slide.
        androidx.test.espresso.PerformException last = null;
        for (int i = 0; i < 12; i++) {
            try {
                onView(withId(R.id.nav_view)).perform(NavigationViewActions.navigateTo(menuId));
                return;
            } catch (androidx.test.espresso.PerformException e) {
                last = e;
                android.os.SystemClock.sleep(250);
            }
        }
        throw last;
    }

    private static void assumeShareConfigured() {
        org.junit.Assume.assumeTrue("no SMB share configured on this device; share tests skipped",
                com.hippo.ehviewer.storage.NetworkStorage.active().isConfigured()
                        && com.hippo.ehviewer.Settings.getNetworkStorageEnabled());
    }

    private void openInventory() {
        assumeShareConfigured();
        openFromDrawer(R.id.nav_local_inventory);
        assertTrue("no gallery rows appeared from the share",
                Boolean.TRUE.equals(
                        mDevice.wait(Until.hasObject(By.res(mPkg, "title")), SHARE_MS)));
        assertFalse("inventory arrived empty",
                mDevice.findObjects(By.res(mPkg, "title")).isEmpty());
        // "SMB", not displayName(): a round trip would not catch a raw placeholder.
        assertTrue("scene title does not carry the protocol name",
                mDevice.hasObject(By.text(androidx.test.platform.app.InstrumentationRegistry
                        .getInstrumentation().getTargetContext()
                        .getString(R.string.local_inventory, "SMB"))));
    }

    private void openFirstGalleryDetail() {
        openInventory();
        UiObject2 first = mDevice.findObject(By.res(mPkg, "title"));
        assertNotNull(first);
        first.click();
        assertTrue("gallery detail did not arrive (no READ button)",
                Boolean.TRUE.equals(
                        mDevice.wait(Until.hasObject(By.res(mPkg, "read")), SHARE_MS)));
    }

    private void dismissOneTimeOverlays() {
        androidx.test.uiautomator.BySelector[] peelable = {
                By.res(mPkg, "accept"),       // WarningScene
                By.res(mPkg, "guest_mode"),   // SignInScene — guest, never credentials
                By.res(mPkg, "ok"),           // AnalyticsScene / SelectSiteScene
                // textAllCaps: matched via the app's own string so any locale peels it.
                By.text(showcaseButtonText()),
                // POST_NOTIFICATIONS (#103); the text covers OEM dialogs that rename the AOSP id.
                By.res("com.android.permissioncontroller:id/permission_allow_button"),
                By.text(Pattern.compile("Allow|ALLOW|允许")),
        };
        for (androidx.test.uiautomator.BySelector s : peelable) {
            UiObject2 o = mDevice.findObject(s);
            if (o != null) {
                o.click();
                mDevice.waitForIdle();
                return;
            }
        }
    }

    private static String showcaseButtonText() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getString(R.string.get_it).toUpperCase(java.util.Locale.getDefault());
    }

    private androidx.test.uiautomator.BySelector withTextOf(int resId) {
        return By.text(InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getString(resId));
    }

    private boolean waitAny(long timeoutMs, androidx.test.uiautomator.BySelector... selectors) {
        long end = android.os.SystemClock.uptimeMillis() + timeoutMs;
        while (android.os.SystemClock.uptimeMillis() < end) {
            for (androidx.test.uiautomator.BySelector s : selectors) {
                if (mDevice.hasObject(s)) {
                    return true;
                }
            }
            android.os.SystemClock.sleep(250);
        }
        return false;
    }

    private String awaitLogLine(Pattern pattern, long timeoutMs) throws IOException {
        long end = android.os.SystemClock.uptimeMillis() + timeoutMs;
        while (android.os.SystemClock.uptimeMillis() < end) {
            String dump = mDevice.executeShellCommand(
                    "logcat -d -s SmbPerf:V SmbStorage:V");
            for (String line : dump.split("\n")) {
                if (pattern.matcher(line).find()) {
                    return line.trim();
                }
            }
            android.os.SystemClock.sleep(250);
        }
        return null;
    }
}
