/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import jcifs.CIFSContext;
import jcifs.smb.NtlmPasswordAuthenticator;

/** The protocol-specific floor (#97): configuration gating, credential wiring, and the base-context cache. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbConnectionTest {

    @Before
    public void setUp() throws Exception {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(Settings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(Settings.KEY_SMB_SHARE_NAME, "share");
        Settings.putString(Settings.KEY_SMB_SHARE_PATH, "galleries");
        Settings.putString(Settings.KEY_SMB_USERNAME, "");
        Settings.putString(Settings.KEY_SMB_PASSWORD, "");
        resetBaseContextCache();
    }

    /** The cache is static and the suite shares one JVM; every test starts from a cold one. */
    private static void resetBaseContextCache() throws Exception {
        Field ctx = SmbConnection.class.getDeclaredField("sBase");
        ctx.setAccessible(true);
        ctx.set(null, null);
    }

    @Test
    public void configurationNeedsBothHostAndShare() {
        assertTrue(SmbConnection.isConfigured());
        Settings.putString(Settings.KEY_SMB_HOST, "");
        assertFalse(SmbConnection.isConfigured());
        Settings.putString(Settings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(Settings.KEY_SMB_SHARE_NAME, "");
        assertFalse(SmbConnection.isConfigured());
    }

    /** One base context per setting, not per call — the cache is what keeps jcifs' connection pool shared. */
    @Test
    public void theBaseContextIsOneInstanceWhileTheSettingHoldsStill() {
        Settings.putBoolean(Settings.KEY_SMB_SIGNING_DISABLED, true);
        assertSame(SmbConnection.buildContext(), SmbConnection.buildContext());
    }

    /**
     * Flipping the signing setting is the one thing that rebuilds the base context — the
     * no-signing path needs its own PropertyConfiguration and its own pool.
     */
    @Test
    public void flippingSigningRebuildsTheBaseContext() {
        CIFSContext before = SmbConnection.buildContext();
        Settings.putBoolean(Settings.KEY_SMB_SIGNING_DISABLED, true);
        CIFSContext after = SmbConnection.buildContext();
        assertNotSame(before, after);
        // And it is itself cached until the setting moves again.
        assertSame(after, SmbConnection.buildContext());
    }

    /** A username in settings must reach jcifs as NTLM credentials, verbatim. */
    @Test
    public void credentialsComeFromSettings() {
        Settings.putString(Settings.KEY_SMB_USERNAME, "panda");
        Settings.putString(Settings.KEY_SMB_PASSWORD, "bamboo");
        CIFSContext context = SmbConnection.buildContext();
        NtlmPasswordAuthenticator credentials =
                (NtlmPasswordAuthenticator) context.getCredentials();
        assertEquals("panda", credentials.getUsername());
        assertEquals("bamboo", credentials.getPassword());
    }

    /** The URL the whole layer builds on is exactly the four settings through SmbPaths — the normalized accessors, not the raw strings, which is why the expe */
    @Test
    public void theShareUrlIsComposedFromSettings() {
        assertEquals(
                SmbPaths.buildShareUrl(Settings.getSmbHost(), Settings.getSmbPort(),
                        Settings.getSmbShareName(), Settings.getSmbSharePath()),
                SmbConnection.buildSmbUrl());
        assertEquals(
                SmbPaths.buildGalleryRootUrl(SmbConnection.buildSmbUrl()),
                SmbConnection.galleryRootUrl());
    }
}
