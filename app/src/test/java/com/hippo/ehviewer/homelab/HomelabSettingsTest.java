/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** When pages go to the homelab, and which URLs it may use (#207). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class HomelabSettingsTest {

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
    }

    @Test
    public void pagesGoToTheHomelabOnlyWhenOnRemoteAndWithAUrl() {
        assertFalse("off by default", HomelabSettings.sendsPagesToHomelab());

        Settings.putBoolean(HomelabSettings.KEY_SR_ENABLED, true);
        assertFalse("no URL", HomelabSettings.sendsPagesToHomelab());

        Settings.putString(HomelabSettings.KEY_SR_URL, "http://10.0.0.5:8790");
        assertTrue(HomelabSettings.sendsPagesToHomelab());

        Settings.putBoolean(HomelabSettings.KEY_SR_LOCAL, true);
        assertFalse("local", HomelabSettings.sendsPagesToHomelab());

        Settings.putBoolean(HomelabSettings.KEY_SR_LOCAL, false);
        Settings.putString(HomelabSettings.KEY_SR_URL, "   ");
        assertFalse("blank URL", HomelabSettings.sendsPagesToHomelab());
    }

    @Test
    public void theUrlLosesSpacesAndTrailingSlashes() {
        Settings.putString(HomelabSettings.KEY_SR_URL, "  http://10.0.0.5:8790// ");

        assertEquals("http://10.0.0.5:8790", HomelabSettings.getSuperResolutionUrl());
    }

    @Test
    public void plainHttpIsAllowedOnlyForALanAddress() {
        String[] allowed = {
                "http://10.0.0.5:8790",
                "http://172.16.0.1",
                "http://172.31.255.254:8790",
                "http://192.168.1.10:8790/",
                "https://sr.example.com",
                "https://8.8.8.8:8790",
        };
        String[] refused = {
                "http://172.15.255.254",
                "http://172.32.0.1",
                "http://192.167.1.1",
                "http://192.169.1.1",
                "http://8.8.8.8:8790",
                "http://sr.example.com:8790",
                "http://[fd00::1]:8790",
                "ftp://192.168.1.10",
                "192.168.1.10:8790",
        };
        for (String url : allowed) {
            assertTrue(url, HomelabSettings.isAllowedUrl(url));
        }
        for (String url : refused) {
            assertFalse(url, HomelabSettings.isAllowedUrl(url));
        }
    }

    @Test
    public void aStoredUrlThatBreaksTheRuleIsNotUsed() {
        Settings.putBoolean(HomelabSettings.KEY_SR_ENABLED, true);
        Settings.putString(HomelabSettings.KEY_SR_URL, "http://sr.example.com:8790");

        assertEquals("", HomelabSettings.getSuperResolutionUrl());
        assertFalse(HomelabSettings.sendsPagesToHomelab());
    }
}
