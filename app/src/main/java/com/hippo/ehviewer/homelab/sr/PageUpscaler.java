/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.homelab.sr;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.homelab.HomelabSettings;
import com.hippo.lib.image.Image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Super-resolution while reading (#207): a decoded page goes to the homelab at the scale its {@link ScaleTarget}
 * asks for, and the sharper copy comes back through {@link Delivery}. It lives only in memory: nothing is written
 * to disk.
 */
public final class PageUpscaler {

    private static final String TAG = "PageUpscaler";
    private static final int WORKERS = 2;
    /** Pages waiting for a worker; the oldest is dropped when more arrive, the reader has moved past it. */
    private static final int MAX_WAITING = 2;

    /** Hands an upscaled page back to the reader. */
    public interface Delivery {
        void deliver(int index, @NonNull Image image);
    }

    private final Delivery mDelivery;
    private Resources mResources;
    private CompanionClient mClient;
    private ExecutorService mWorkers;
    private int mLongSide;
    private boolean mStopped;

    public PageUpscaler(@NonNull Delivery delivery) {
        mDelivery = delivery;
    }

    PageUpscaler(@NonNull Delivery delivery, @NonNull Resources resources, @NonNull CompanionClient client,
                 @NonNull ExecutorService workers, int longSide) {
        mDelivery = delivery;
        mResources = resources;
        mClient = client;
        mWorkers = workers;
        mLongSide = longSide;
    }

    /** Called with every page the reader decodes, before it is shown. */
    public void onPage(int index, @NonNull Image image) {
        if (!HomelabSettings.sendsPagesToHomelab() || image.getAnimated()) {
            return;
        }
        synchronized (this) {
            if (mStopped) {
                return;
            }
            startIfNeeded();
            int scale = HomelabSettings.getScaleTarget().scaleFor(mLongSide, image.getWidth(), image.getHeight());
            if (scale == ScaleRule.NONE) {
                return;
            }
            Bitmap page = copyOf(image);
            if (page != null) {
                mWorkers.execute(new Job(index, scale, page, HomelabSettings.getSuperResolutionUrl(),
                        HomelabSettings.getPassword()));
            }
        }
    }

    /** The reader is closing: cancel the calls in flight and drop what waits. */
    public void stop() {
        ExecutorService workers;
        synchronized (this) {
            mStopped = true;
            workers = mWorkers;
        }
        if (workers == null) {
            return;
        }
        mClient.cancelAll();
        for (Runnable waiting : workers.shutdownNow()) {
            ((Job) waiting).drop();
        }
    }

    private void startIfNeeded() {
        if (mWorkers != null) {
            return;
        }
        Context app = EhApplication.getInstance();
        mResources = app.getResources();
        mLongSide = DeviceScreen.longSide(app);
        mClient = new CompanionClient();
        mWorkers = new ThreadPoolExecutor(WORKERS, WORKERS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingDeque<>(MAX_WAITING), new DropOldest());
    }

    private void deliver(int index, @NonNull Image image) {
        synchronized (this) {
            if (!mStopped) {
                mDelivery.deliver(index, image);
                return;
            }
        }
        image.recycle();
    }

    /** A copy of the page's pixels: the reader may recycle its own bitmap while the page is on its way. */
    @Nullable
    private static Bitmap copyOf(@NonNull Image image) {
        Drawable drawable;
        try {
            drawable = image.getDrawable();
        } catch (IllegalStateException recycled) {
            return null;
        }
        try {
            return drawable instanceof BitmapDrawable
                    ? ((BitmapDrawable) drawable).getBitmap().copy(Bitmap.Config.ARGB_8888, false) : null;
        } finally {
            image.release();
        }
    }

    @NonNull
    private static byte[] encodePng(@NonNull Bitmap page) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(page.getByteCount() / 4);
        if (!page.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            throw new IOException("cannot encode the page");
        }
        return out.toByteArray();
    }

    @NonNull
    private Image decode(@NonNull byte[] bytes) throws IOException {
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        Image image = bitmap == null ? null : Image.decode(new BitmapDrawable(mResources, bitmap), false);
        if (image == null) {
            throw new IOException("the companion did not answer with an image");
        }
        return image;
    }

    private final class Job implements Runnable {

        private final int mIndex;
        private final int mScale;
        private final Bitmap mPage;
        private final String mUrl;
        private final String mPassword;

        Job(int index, int scale, @NonNull Bitmap page, @NonNull String url, @NonNull String password) {
            mIndex = index;
            mScale = scale;
            mPage = page;
            mUrl = url;
            mPassword = password;
        }

        @Override
        public void run() {
            try {
                byte[] png;
                try {
                    png = encodePng(mPage);
                } finally {
                    mPage.recycle();
                }
                deliver(mIndex, decode(mClient.upscale(mUrl, mPassword, png, mScale)));
            } catch (IOException e) {
                Log.w(TAG, "Page " + (mIndex + 1) + " stays as it is: " + e.getMessage());
            }
        }

        void drop() {
            mPage.recycle();
        }
    }

    /** Drops the page that waited longest, freeing its pixels, to make room for the newest one. */
    private static final class DropOldest implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable job, ThreadPoolExecutor workers) {
            if (workers.isShutdown()) {
                ((Job) job).drop();
                return;
            }
            Runnable oldest = workers.getQueue().poll();
            if (oldest != null) {
                ((Job) oldest).drop();
            }
            workers.execute(job);
        }
    }
}
