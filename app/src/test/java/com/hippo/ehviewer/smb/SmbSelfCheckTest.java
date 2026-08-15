/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.storage.ConnectionDraft;
import com.hippo.ehviewer.storage.SelfCheck;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

/** The pre-save probe (#133): three cumulative stages, and the temp file never survives. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbSelfCheckTest.ShadowSmbFile.class},
        instrumentedPackages = {"jcifs.smb", "com.hippo.ehviewer.smb"})
public class SmbSelfCheckTest {

    static boolean connectFails;
    static boolean listFails;
    static boolean writeFails;
    static final List<String> deleted = new ArrayList<>();
    static final Map<String, ByteArrayOutputStream> written = new HashMap<>();

    @Implements(SmbFile.class)
    public static class ShadowSmbFile {
        @RealObject SmbFile real;

        @Implementation
        protected boolean exists() throws SmbException {
            if (connectFails) {
                throw new SmbException(0xC0000022, false);
            }
            return true;
        }

        @Implementation
        protected String[] list() throws SmbException {
            if (listFails) {
                throw new SmbException(0xC0000022, false);
            }
            return new String[0];
        }

        @Implementation
        protected OutputStream getOutputStream() throws java.io.IOException {
            if (writeFails) {
                throw new java.io.IOException("readonly share");
            }
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            written.put(real.getPath(), sink);
            return sink;
        }

        @Implementation
        protected InputStream getInputStream() {
            ByteArrayOutputStream sink = written.get(real.getPath());
            return new ByteArrayInputStream(
                    sink == null ? new byte[0] : sink.toByteArray());
        }

        @Implementation
        protected void delete() {
            deleted.add(real.getName());
        }

        @Implementation
        protected void mkdirs() {}
    }

    @Before
    public void setUp() {
        connectFails = false;
        listFails = false;
        writeFails = false;
        deleted.clear();
        written.clear();
    }

    private static ConnectionDraft draft() {
        return new ConnectionDraft("192.0.2.7", "445", "share", "/path/",
                "user", "pass", false);
    }

    /** A dead host fails the first stage; the later stages are never claimed. */
    @Test
    public void anUnreachableShareFailsAtConnect() {
        connectFails = true;

        SelfCheck r = SmbSelfCheck.run(draft());

        assertFalse(r.connectOk);
        assertFalse(r.readOk);
        assertFalse(r.writeOk);
    }

    /** Reachable but unlistable: connected, unreadable — not a write problem. */
    @Test
    public void anUnlistableShareFailsAtRead() {
        listFails = true;

        SelfCheck r = SmbSelfCheck.run(draft());

        assertTrue(r.connectOk);
        assertFalse(r.readOk);
        assertFalse(r.readOnly());
    }

    /** The browse-only case: readable, not writable — and reported exactly that way. */
    @Test
    public void aReadOnlyShareFailsAtWriteOnly() {
        writeFails = true;

        SelfCheck r = SmbSelfCheck.run(draft());

        assertTrue(r.connectOk);
        assertTrue(r.readOk);
        assertFalse(r.writeOk);
        assertTrue(r.readOnly());
    }

    /** Write proves itself by reading its own bytes back — and the temp file goes away. */
    @Test
    public void aHealthyShareRoundTripsAndCleansUp() {
        SelfCheck r = SmbSelfCheck.run(draft());

        assertTrue(r.allOk());
        assertEquals("the probe's temp file must be deleted", 1, deleted.size());
        assertTrue("the temp file must use the sweepable name pattern",
                deleted.get(0).endsWith(SmbTempFiles.SUFFIX));
    }

    /** An empty draft is not a connection to probe. */
    @Test
    public void anEmptyDraftFailsImmediately() {
        SelfCheck r = SmbSelfCheck.run(new ConnectionDraft("", "", "", "", "", "", false));

        assertFalse(r.connectOk);
    }
}
