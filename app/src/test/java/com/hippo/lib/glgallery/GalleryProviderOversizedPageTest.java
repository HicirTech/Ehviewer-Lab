/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.lib.glgallery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;

import com.hippo.lib.image.Image;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A page larger than the whole reader cache reaches the reader instead of being recycled on arrival (U23, #207). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GalleryProviderOversizedPageTest {

    /** The cache counts 4 bytes a pixel: 5800 x 5800 is above its 128 MiB ceiling. ALPHA_8 allocates a quarter. */
    private static final int OVERSIZED_SIDE = 5800;
    private static final int ORDINARY_SIDE = 100;

    private final RecordingProvider mProvider = new RecordingProvider();

    private static Bitmap page(int side) {
        return Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8);
    }

    private static Image imageOf(Bitmap page) {
        return Image.decode(new BitmapDrawable(RuntimeEnvironment.getApplication().getResources(), page), false);
    }

    @Test
    public void aPageLargerThanTheCacheStaysUsableAndUncached() {
        Bitmap page = page(OVERSIZED_SIDE);

        mProvider.notifyPageSucceed(0, imageOf(page));

        assertFalse(page.isRecycled());
        mProvider.request(0);
        assertEquals(Collections.singletonList(0), mProvider.requested);
    }

    @Test
    public void anOrdinaryPageIsStillCached() {
        mProvider.notifyPageSucceed(0, imageOf(page(ORDINARY_SIDE)));

        mProvider.request(0);

        assertTrue(mProvider.requested.isEmpty());
    }

    private static final class RecordingProvider extends GalleryProvider {

        final List<Integer> requested = new ArrayList<>();

        @Override
        public int size() {
            return 1;
        }

        @Override
        protected void onRequest(int index) {
            requested.add(index);
        }

        @Override
        protected void onForceRequest(int index) {
        }

        @Override
        protected void onCancelRequest(int index) {
        }

        @Override
        public String getError() {
            return null;
        }
    }
}
