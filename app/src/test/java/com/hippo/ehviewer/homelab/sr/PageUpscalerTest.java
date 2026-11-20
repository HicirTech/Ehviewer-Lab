/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;

import androidx.annotation.NonNull;

import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.homelab.HomelabSettings;
import com.hippo.lib.image.Image;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Pages go to the homelab and the sharper copies come back to the reader, in memory only (#207). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PageUpscalerTest {

    private static final int LONG_SIDE = 200;

    private final List<Integer> mDelivered = Collections.synchronizedList(new ArrayList<>());
    private final List<Image> mImages = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch mDelivery = new CountDownLatch(1);
    private Resources mResources;
    private FakeCompanion mCompanion;
    private ExecutorService mWorkers;

    @Before
    public void setUp() throws IOException {
        Settings.initialize(RuntimeEnvironment.getApplication());
        mResources = RuntimeEnvironment.getApplication().getResources();
        mCompanion = new FakeCompanion();
        mCompanion.answer = jpeg(200, 320);
        Settings.putBoolean(HomelabSettings.KEY_SR_ENABLED, true);
        Settings.putString(HomelabSettings.KEY_SR_URL, mCompanion.lanUrl());
        Settings.putString(HomelabSettings.KEY_PASSWORD, "pass-1234");
        mWorkers = Executors.newSingleThreadExecutor();
    }

    @After
    public void tearDown() throws IOException {
        mWorkers.shutdownNow();
        mCompanion.close();
    }

    private PageUpscaler upscaler() {
        return new PageUpscaler(this::record, mResources, new CompanionClient(FakeCompanion.LOOPBACK_DNS), mWorkers,
                LONG_SIDE);
    }

    private void record(int index, @NonNull Image image) {
        mDelivered.add(index);
        mImages.add(image);
        mDelivery.countDown();
    }

    private Image page(int width, int height) {
        return Image.decode(new BitmapDrawable(mResources, Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)),
                false);
    }

    private static byte[] jpeg(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out);
        return out.toByteArray();
    }

    /** Waits until the jobs queued so far have run. */
    private void flush() throws Exception {
        mWorkers.submit(() -> {}).get(30, TimeUnit.SECONDS);
    }

    @Test
    public void aSmallPageComesBackUpscaledForTheSameIndex() throws Exception {
        upscaler().onPage(7, page(50, 80));

        assertTrue(mDelivery.await(30, TimeUnit.SECONDS));
        assertEquals(Collections.singletonList(7), mDelivered);
        assertEquals(200, mImages.get(0).getWidth());
        assertEquals(320, mImages.get(0).getHeight());
        assertEquals("/v1/upscale?scale=4", mCompanion.target);
        assertEquals("Bearer pass-1234", mCompanion.authorization);
    }

    @Test
    public void nothingIsSentWhenSuperResolutionIsOff() throws Exception {
        Settings.putBoolean(HomelabSettings.KEY_SR_ENABLED, false);

        upscaler().onPage(0, page(50, 80));
        flush();

        assertEquals(0, mCompanion.requests.get());
        assertTrue(mDelivered.isEmpty());
    }

    @Test
    public void aPageAsLargeAsTheScreenIsNotSent() throws Exception {
        upscaler().onPage(0, page(LONG_SIDE, 300));
        flush();

        assertEquals(0, mCompanion.requests.get());
        assertTrue(mDelivered.isEmpty());
    }

    @Test
    public void closingTheReaderCancelsThePageOnItsWay() throws Exception {
        mCompanion.holdAnswer = true;
        PageUpscaler upscaler = upscaler();
        upscaler.onPage(3, page(50, 80));
        assertTrue(mCompanion.requested.await(30, TimeUnit.SECONDS));

        upscaler.stop();
        mCompanion.release.countDown();
        assertTrue(mWorkers.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(mDelivered.isEmpty());
    }
}
