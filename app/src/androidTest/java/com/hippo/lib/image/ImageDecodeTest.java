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

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * The real ImageDecoder must accept the in-memory echo stream (#155) — the exact configuration
 * that crashed in production. The Robolectric shadow cannot exercise this arm, so it runs here.
 */
@RunWith(AndroidJUnit4.class)
public class ImageDecodeTest {

    @Test
    public void aMemoryStreamDecodesOnDevice() {
        Bitmap bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);

        Image image = Image.decode(new ByteArrayInputStream(bytes.toByteArray()), false);

        assertNotNull("a ByteArrayInputStream must decode on a real device", image);
        assertEquals(8, image.getWidth());
        assertEquals(6, image.getHeight());
    }
}
