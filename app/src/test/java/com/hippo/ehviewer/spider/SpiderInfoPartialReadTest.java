/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.spider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** A .ehviewer that stops reading midway still yields its header and earlier tokens (#164). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class SpiderInfoPartialReadTest {

    /** startPage 0xc, gid 123456, 3 pages. */
    private static final String HEADER = "VERSION2\n0000000c\n123456\nabcdef01\n1\n2\n20\n3\n";

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    public void aWholeFileReadsEveryToken() {
        SpiderInfo info = SpiderInfo.read(bytes(HEADER + "0 tok0\n1 tok1\n2 tok2\n"));

        assertNotNull(info);
        assertEquals(12, info.startPage);
        assertEquals(3, info.pTokenMap.size());
    }

    @Test
    public void anOverLongTokenLineKeepsTheHeaderAndTheTokensBeforeIt() {
        StringBuilder longLine = new StringBuilder("1 ");
        for (int i = 0; i < 3000; i++) {
            longLine.append('x');
        }
        SpiderInfo info = SpiderInfo.read(bytes(HEADER + "0 tok0\n" + longLine + "\n2 tok2\n"));

        assertNotNull(info);
        assertEquals(12, info.startPage);
        assertEquals(123456L, info.gid);
        assertEquals("tok0", info.pTokenMap.get(0));
        assertNull(info.pTokenMap.get(1));
    }

    @Test
    public void aReadFailingMidFileKeepsTheHeaderAndTheTokensBeforeIt() {
        byte[] head = (HEADER + "0 tok0\n1 to").getBytes(StandardCharsets.US_ASCII);
        InputStream failing = new InputStream() {
            private int pos;

            @Override
            public int read() throws IOException {
                if (pos < head.length) {
                    return head[pos++];
                }
                throw new IOException("connection reset");
            }
        };

        SpiderInfo info = SpiderInfo.read(failing);

        assertNotNull(info);
        assertEquals(12, info.startPage);
        assertEquals("tok0", info.pTokenMap.get(0));
    }
}
