/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.smb;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.util.ExceptionUtils;

import jcifs.smb.NtStatus;
import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbException;

/**
 * jcifs failures in the app's own error dialect (#133): short localized reasons, the way
 * {@link ExceptionUtils#getReadableString} speaks — never a raw exception string. Walks the
 * cause chain because jcifs wraps the interesting exception two levels deep.
 */
final class SmbErrors {

    private SmbErrors() {}

    @NonNull
    static String describe(@NonNull Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SmbAuthException) {
                return GetText.getString(R.string.smb_error_auth);
            }
            if (t instanceof SmbException) {
                int status = ((SmbException) t).getNtStatus();
                if (status == NtStatus.NT_STATUS_LOGON_FAILURE) {
                    return GetText.getString(R.string.smb_error_auth);
                }
                if (status == NtStatus.NT_STATUS_BAD_NETWORK_NAME) {
                    return GetText.getString(R.string.smb_error_share_not_found);
                }
                if (status == NtStatus.NT_STATUS_ACCESS_DENIED
                        || status == NtStatus.NT_STATUS_NETWORK_ACCESS_DENIED) {
                    return GetText.getString(R.string.smb_error_access_denied);
                }
            }
            if (t instanceof java.net.UnknownHostException
                    // jcifs reports a failed NetBIOS lookup as "0.0.0.0<00>/host" inside a
                    // transport exception; the type is not exported, the shape is stable.
                    || (t.getMessage() != null && t.getMessage().contains("<00>"))) {
                return GetText.getString(R.string.error_unknown_host);
            }
            if (t instanceof java.net.SocketTimeoutException) {
                return GetText.getString(R.string.error_timeout);
            }
            if (t instanceof java.net.ConnectException
                    || t instanceof java.net.NoRouteToHostException) {
                return GetText.getString(R.string.error_socket);
            }
        }
        return ExceptionUtils.getReadableString(e);
    }
}
