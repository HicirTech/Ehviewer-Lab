/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** What the app sends to the companion's /v1/upscale and how it reads the answer (#207). */
public class CompanionClientTest {

    private FakeCompanion mCompanion;

    @Before
    public void setUp() throws IOException {
        mCompanion = new FakeCompanion();
        mCompanion.answer = "upscaled".getBytes(StandardCharsets.UTF_8);
    }

    @After
    public void tearDown() throws IOException {
        mCompanion.close();
    }

    @Test
    public void sendsThePageWithScaleAndPasswordAndReturnsTheAnswer() throws IOException {
        byte[] page = {1, 2, 3, 4};

        byte[] answer = new CompanionClient().upscale(mCompanion.baseUrl(), "pass-1234", page, 3);

        assertEquals("upscaled", new String(answer, StandardCharsets.UTF_8));
        assertEquals("POST", mCompanion.method);
        assertEquals("/v1/upscale?scale=3", mCompanion.target);
        assertEquals("Bearer pass-1234", mCompanion.authorization);
        assertArrayEquals(page, mCompanion.body);
    }

    @Test
    public void aRefusalIsAnError() {
        mCompanion.status = 401;

        assertThrows(IOException.class,
                () -> new CompanionClient().upscale(mCompanion.baseUrl(), "wrong-pass", new byte[]{1}, 2));
    }

    @Test
    public void anAddressThatIsNotHttpIsAnError() {
        assertThrows(IOException.class,
                () -> new CompanionClient().upscale("ftp://127.0.0.1", "pass-1234", new byte[]{1}, 2));
    }
}
