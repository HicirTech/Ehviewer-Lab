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
import jcifs.util.transport.ConnectionTimeoutException;
import jcifs.util.transport.RequestTimeoutException;
import jcifs.util.transport.TransportException;

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

    @Test
    public void aWrappedUnknownHostReadsAsUnknownHost() {
        CIFSException wrapped = new CIFSException("Failed to connect",
                new java.net.UnknownHostException("no.such.host"));
        assertEquals(expect(R.string.error_unknown_host), SmbErrors.describe(wrapped));
    }

    @Test
    public void aFailedNetbiosLookupReadsAsUnknownHost() {
        SmbException failed = new SmbException("Failed to connect to server",
                new java.net.UnknownHostException("NOSUCHNB"));
        assertEquals(expect(R.string.error_unknown_host), SmbErrors.describe(failed));
    }

    @Test
    public void anUnreachableIpHostReadsAsANetworkError() {
        assertEquals(expect(R.string.error_socket), SmbErrors.describe(
                failedToConnect(new java.net.NoRouteToHostException("No route to host"))));
    }

    @Test
    public void aRefusedIpHostReadsAsANetworkError() {
        assertEquals(expect(R.string.error_socket), SmbErrors.describe(
                failedToConnect(new java.net.ConnectException("Connection refused"))));
    }

    @Test
    public void aHostThatNeverAnswersReadsAsAConnectTimeout() {
        SmbException silent = new SmbException("Failed to connect: 0.0.0.0<00>/192.0.2.99",
                new ConnectionTimeoutException("Connection timeout"));
        assertEquals(expect(R.string.smb_error_connect_timeout), SmbErrors.describe(silent));
    }

    @Test
    public void aSocketConnectTimeoutReadsAsAConnectTimeout() {
        CIFSException wrapped = new CIFSException("Failed to connect",
                new java.net.SocketTimeoutException("connect timed out"));
        assertEquals(expect(R.string.smb_error_connect_timeout), SmbErrors.describe(wrapped));
    }

    @Test
    public void aRequestLeftUnansweredReadsAsAReadTimeout() {
        assertEquals(expect(R.string.smb_error_read_timeout), SmbErrors.describe(
                new RequestTimeoutException("Transport1 timedout waiting for response to Smb2ReadRequest")));
    }

    /** What jcifs-ng 2.1.10 throws when an IP host fails: it names the host 0.0.0.0<00>. */
    private static SmbException failedToConnect(Exception cause) {
        return new SmbException("Failed to connect: 0.0.0.0<00>/192.0.2.99", new TransportException(cause));
    }

    @Test
    public void anUnknownFailureFallsBackToTheAppExplainer() {
        assertEquals(com.hippo.util.ExceptionUtils.getReadableString(new IllegalStateException("x")),
                SmbErrors.describe(new IllegalStateException("x")));
    }
}
