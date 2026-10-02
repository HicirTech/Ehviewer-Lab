/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.conaco;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hippo.streampipe.InputStreamPipe;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;

/**
 * A value decoded from a DataContainer is copied into the disk cache (upstream 2.0.2.5) unless
 * the container forbids it. The SMB containers do: the share is the only durable copy of what
 * they serve, and a copy costs them a second read off it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
public class ConacoTaskDiskCopyTest {

    private static final String KEY = "disk-copy-key";
    /** Long enough that a busy CI runner is not a failure; only a stuck test ever waits this. */
    private static final long TIMEOUT_MS = 10_000;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Conaco<String> conaco;

    /** Serves fixed bytes and counts how often it is read. */
    private static final class CountingContainer implements DataContainer {
        final AtomicInteger gets = new AtomicInteger();
        private final boolean allowDiskCopy;

        CountingContainer(boolean allowDiskCopy) {
            this.allowDiskCopy = allowDiskCopy;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void onUrlMoved(String requestUrl, String responseUrl) {
        }

        @Override
        public boolean save(InputStream is, long length, @Nullable String mediaType,
                            @Nullable ProgressNotifier notify) {
            return false;
        }

        @Override
        public InputStreamPipe get() {
            gets.incrementAndGet();
            return new InputStreamPipe() {
                @Override public void obtain() {}

                @Override public void release() {}

                @Override
                public InputStream open() throws IOException {
                    return new ByteArrayInputStream("cover".getBytes(StandardCharsets.US_ASCII));
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void remove() {
        }

        @Override
        public boolean allowDiskCopy() {
            return allowDiskCopy;
        }
    }

    /** Records the value it is handed. */
    private static final class RecordingUnikery implements Unikery<String> {
        private int taskId = INVALID_ID;
        volatile String value;

        @Override public void setTaskId(int id) { taskId = id; }

        @Override public int getTaskId() { return taskId; }

        @Override public void onMiss(int source) {}

        @Override public void onRequest() {}

        @Override public void onProgress(long single, long received, long total) {}

        @Override public void onWait() {}

        @Override
        public boolean onGetValue(@NonNull String value, int source) {
            this.value = value;
            return true;
        }

        @Override public void onFailure() {}

        @Override public void onCancel() {}
    }

    @Before
    public void setUp() throws IOException {
        Conaco.Builder<String> builder = new Conaco.Builder<>();
        builder.hasDiskCache = true;
        builder.diskCacheDir = folder.newFolder("disk");
        builder.diskCacheMaxSize = 1024 * 1024;
        builder.okHttpClient = new OkHttpClient();
        builder.objectHelper = new ValueHelper<String>() {
            @Override
            public String decode(@NonNull InputStreamPipe isPipe) {
                return decode(isPipe, false);
            }

            @Override
            public String decode(@NonNull InputStreamPipe isPipe, boolean hardware) {
                return "decoded";
            }

            @Override public int sizeOf(@NonNull String key, @NonNull String value) { return 1; }

            @Override public void onAddToMemoryCache(@NonNull String oldValue) {}

            @Override public void onRemoveFromMemoryCache(@NonNull String key, @NonNull String oldValue) {}

            @Override public boolean useMemoryCache(@NonNull String key, String holder) { return false; }
        };
        conaco = builder.build();
    }

    private String load(CountingContainer container) {
        RecordingUnikery unikery = new RecordingUnikery();
        conaco.load(new ConacoTask.Builder<String>()
                .setUnikery(unikery)
                .setKey(KEY)
                .setUrl("smb-cover://1")
                .setDataContainer(container)
                .setUseNetwork(false));
        // Real time: the disk thread is a real thread, and Robolectric's SystemClock is not.
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        while (unikery.value == null && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return unikery.value;
    }

    @Test
    public void aContainerThatForbidsItIsReadOnceAndLeavesNoDiskCopy() {
        CountingContainer container = new CountingContainer(false);

        assertNotNull(load(container));

        assertEquals(1, container.gets.get());
        assertFalse(conaco.getBeerBelly().getDiskCache().contain(KEY));
    }

    @Test
    public void anyOtherContainerIsCopiedToDisk() {
        CountingContainer container = new CountingContainer(true);

        assertNotNull(load(container));

        assertEquals(2, container.gets.get());
        assertTrue(conaco.getBeerBelly().getDiskCache().contain(KEY));
    }
}
