/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.localinventory;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.hippo.conaco.Conaco;
import com.hippo.conaco.ConacoTask;
import com.hippo.conaco.Unikery;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.lib.image.Image;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

import java.util.concurrent.atomic.AtomicBoolean;

/** A library cover opens the reader however its image arrived (#199). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {InventoryCoverTapTest.ShadowEhApplication.class,
                   InventoryCoverTapTest.ShadowConaco.class},
        instrumentedPackages = {"com.hippo.ehviewer.EhApplication", "com.hippo.conaco.Conaco"})
public class InventoryCoverTapTest {

    @Implements(EhApplication.class)
    public static class ShadowEhApplication {
        @Implementation
        protected static Conaco<Image> getConaco(Context context) {
            return Shadow.newInstanceOf(Conaco.class);
        }
    }

    /** Never takes a task: the state a memory-cache hit leaves behind. */
    @Implements(Conaco.class)
    public static class ShadowConaco {
        @Implementation
        protected void load(ConacoTask.Builder<?> builder) {}

        @Implementation
        protected void cancel(Unikery<?> unikery) {}
    }

    private Activity activity;
    private LocalInventoryScene.InventoryHolder holder;
    private final AtomicBoolean readerOpened = new AtomicBoolean();

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        View item = LayoutInflater.from(activity)
                .inflate(R.layout.item_gallery_list, new FrameLayout(activity), false);
        holder = new LocalInventoryScene.InventoryHolder(item);
        // As bind() does: load the cover, then send its tap to the reader.
        holder.thumb.load("thumb-key", "smb-cover://1", null, false, false);
        holder.thumb.setOnClickListener(v -> readerOpened.set(true));
    }

    @Test
    public void aCoverReloadedOnAttachStillOpensTheReader() {
        activity.setContentView(holder.itemView);

        assertTrue(holder.thumb.performClick());
        assertTrue(readerOpened.get());
    }

    @Test
    public void aCoverThatFailedToLoadStillOpensTheReader() {
        holder.thumb.onFailure();

        assertTrue(holder.thumb.performClick());
        assertTrue(readerOpened.get());
    }
}
