/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.ui.scene.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.dao.DownloadInfo;
import com.hippo.ehviewer.smb.SmbDirectDownloader;
import com.hippo.ehviewer.storage.NetworkStorage;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** A move skips local imports, whose gids are made up, and says so (#88). */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {SmbDownloadsDelegateTest.ShadowSmbDirectDownloader.class},
        instrumentedPackages = {"com.hippo.ehviewer.smb"})
public class SmbDownloadsDelegateTest {

    private static final String ARCHIVE_URI =
            "content://com.android.externalstorage.documents/document/primary%3Aa.zip";
    private static final String ALBUM_URI = "local-album:content://tree/primary%3APictures";

    static final List<Long> moved = Collections.synchronizedList(new ArrayList<>());

    private Context context;
    private SmbDownloadsDelegate delegate;

    @Implements(SmbDirectDownloader.class)
    public static class ShadowSmbDirectDownloader {
        @Implementation
        protected void startMove(Context context, GalleryInfo info) {
            moved.add(info.gid);
        }
    }

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.initialize(context);
        moved.clear();
        delegate = new SmbDownloadsDelegate(new SmbDownloadsDelegate.Host() {
            @Override
            public Context context() {
                return context;
            }

            @Override
            public void onTasksChanged() {
            }

            @Override
            public DownloadInfo infoAt(int position) {
                return null;
            }
        });
    }

    private static DownloadInfo row(long gid, String archiveUri) {
        DownloadInfo info = new DownloadInfo();
        info.gid = gid;
        info.archiveUri = archiveUri;
        return info;
    }

    private String keptMessage() {
        return context.getString(R.string.download_move_to_smb_local_kept,
                NetworkStorage.active().displayName());
    }

    @Test
    public void localImportsAreNeverEnqueued() {
        delegate.moveToShare(context, Arrays.asList(
                row(1727000000000L, ARCHIVE_URI), row(1727000000001L, ALBUM_URI)));

        assertTrue(moved.isEmpty());
        assertEquals("only the 'kept' toast; nothing moved", 1, ShadowToast.shownToastCount());
        assertTrue(ShadowToast.showedToast(keptMessage()));
    }

    @Test
    public void aGalleryMovesWhileTheImportBesideItIsSkipped() {
        delegate.moveToShare(context, Arrays.asList(
                row(4035531L, null), row(1727000000000L, ARCHIVE_URI)));

        assertEquals(Collections.singletonList(4035531L), moved);
        assertTrue(ShadowToast.showedToast(keptMessage()));
    }
}
