/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.lib.image;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.graphics.Bitmap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * The decoder accepts the in-memory echo stream as well as a file stream (#155): the production
 * crash was the decode path casting every pipe to FileInputStream on the way in.
 */
// Pinned below P: Robolectric's ImageDecoder shadow supports neither a mapped buffer nor
// setTargetSampleSize; the P+ arm is covered on-device by ImageDecodeTest.
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 27)
public class ImageTest {

    private byte[] png;

    @Before
    public void setUp() {
        Image.Companion.setScreenWidth(1080);
        Image.Companion.setScreenHeight(1920);
        Bitmap bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        png = bytes.toByteArray();
    }

    /** The echo pipe hands the decoder a memory stream; it must decode, not crash (#155). */
    @Test
    public void aMemoryStreamDecodes() {
        Image image = Image.decode(new ByteArrayInputStream(png), false);

        assertNotNull("a ByteArrayInputStream must decode", image);
        assertEquals(8, image.getWidth());
        assertEquals(6, image.getHeight());
    }

    /** The mmap fast path for real files is untouched. */
    @Test
    public void aFileStreamStillDecodes() throws Exception {
        File file = Files.createTempFile("image-test", ".png").toFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(png);
        }

        Image image = Image.decode(new FileInputStream(file), false);

        assertNotNull(image);
        assertEquals(8, image.getWidth());
        assertEquals(6, image.getHeight());
    }
}
