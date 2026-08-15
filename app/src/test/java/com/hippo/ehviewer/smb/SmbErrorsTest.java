/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertEquals;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import jcifs.CIFSException;
import jcifs.smb.NtStatus;
import jcifs.smb.SmbException;

/** jcifs failures must come out in the app's error dialect, never as raw exception strings. */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SmbErrorsTest {

    @Before
    public void setUp() {
        GetText.initialize(RuntimeEnvironment.getApplication());
    }

    private static String expect(int res) {
        return RuntimeEnvironment.getApplication().getString(res);
    }

    /** A wrong password is the by-far most common failure and must say exactly that. */
    @Test
    public void aLogonFailureReadsAsWrongCredentials() {
        assertEquals(expect(R.string.smb_error_auth),
                SmbErrors.describe(new SmbException(NtStatus.NT_STATUS_LOGON_FAILURE, false)));
    }

    @Test
    public void aBadShareNameReadsAsNoSuchShare() {
        assertEquals(expect(R.string.smb_error_share_not_found),
                SmbErrors.describe(new SmbException(NtStatus.NT_STATUS_BAD_NETWORK_NAME, false)));
    }

    /** jcifs wraps the interesting exception; the cause chain must be walked. */
    @Test
    public void aWrappedUnknownHostReadsAsUnknownHost() {
        CIFSException wrapped = new CIFSException("Failed to connect",
                new java.net.UnknownHostException("no.such.host"));
        assertEquals(expect(R.string.error_unknown_host), SmbErrors.describe(wrapped));
    }

    /** The real-world shape from a dead address: "Failed to connect: 0.0.0.0<00>/192.0.2.99". */
    @Test
    public void aFailedNetbiosLookupReadsAsUnknownHost() {
        assertEquals(expect(R.string.error_unknown_host),
                SmbErrors.describe(new CIFSException("Failed to connect: 0.0.0.0<00>/192.0.2.99")));
    }

    @Test
    public void aWrappedTimeoutReadsAsTimeout() {
        CIFSException wrapped = new CIFSException("Failed to connect",
                new java.net.SocketTimeoutException("connect timed out"));
        assertEquals(expect(R.string.error_timeout), SmbErrors.describe(wrapped));
    }

    /** Anything unrecognised falls back to the app's generic explainer, not to jcifs text. */
    @Test
    public void anUnknownFailureFallsBackToTheAppExplainer() {
        assertEquals(com.hippo.util.ExceptionUtils.getReadableString(new IllegalStateException("x")),
                SmbErrors.describe(new IllegalStateException("x")));
    }
}
