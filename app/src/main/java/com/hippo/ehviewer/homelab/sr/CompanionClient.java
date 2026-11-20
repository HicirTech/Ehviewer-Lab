/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** The super-resolution API of the homelab companion (#206). A client of its own: no E-Hentai cookies or proxy. */
final class CompanionClient {

    private static final MediaType PNG = MediaType.parse("image/png");
    private static final int CONNECT_TIMEOUT_SECONDS = 3;
    private static final int TRANSFER_TIMEOUT_SECONDS = 30;

    private final OkHttpClient mClient;

    CompanionClient() {
        this(Dns.SYSTEM);
    }

    /** Tests resolve a LAN address to their local fake. */
    CompanionClient(@NonNull Dns dns) {
        mClient = new OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /** The page upscaled {@code scale} times, as image bytes; throws when the companion is unreachable or refuses. */
    @NonNull
    byte[] upscale(@NonNull String baseUrl, @NonNull String password, @NonNull byte[] page, int scale)
            throws IOException {
        HttpUrl url = HttpUrl.parse(baseUrl + "/v1/upscale");
        if (url == null) {
            throw new IOException("not an http(s) URL");
        }
        Request request = new Request.Builder()
                .url(url.newBuilder().addQueryParameter("scale", Integer.toString(scale)).build())
                .header("Authorization", "Bearer " + password)
                .post(RequestBody.create(PNG, page))
                .build();
        try (Response response = mClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("companion answered " + response.code());
            }
            return body.bytes();
        }
    }

    /** Aborts the calls in flight, for a reader that is closing. */
    void cancelAll() {
        mClient.dispatcher().cancelAll();
    }
}
