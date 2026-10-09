/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.storage.GalleryRef;
import com.hippo.ehviewer.storage.NetworkStorageSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.IOException;
import java.util.List;

/** Benchmark and auto-tune report why the share could not be listed, not that it is empty. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbMeasurementFailureTest.ShadowSmbInventory.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class SmbMeasurementFailureTest {

    static final String REASON = "Network error";

    @Implements(SmbInventory.class)
    public static class ShadowSmbInventory {
        @Implementation
        protected static List<GalleryRef> listGalleryRefs() throws IOException {
            throw new IOException(REASON);
        }
    }

    @Before
    public void setUp() {
        Settings.initialize(RuntimeEnvironment.getApplication());
        Settings.putString(NetworkStorageSettings.KEY_SMB_HOST, "192.0.2.7");
        Settings.putString(NetworkStorageSettings.KEY_SMB_SHARE_NAME, "share");
    }

    @Test
    public void theBenchmarkReportsWhyTheShareFailed() {
        SmbBenchmark.Result r = SmbBenchmark.run();

        assertFalse(r.ok);
        assertEquals(REASON, r.problem);
    }

    @Test
    public void autoTuneReportsWhyTheShareFailed() {
        SmbAutoTune.Result r = SmbAutoTune.run(null);

        assertFalse(r.ok);
        assertEquals(REASON, r.problem);
    }
}
