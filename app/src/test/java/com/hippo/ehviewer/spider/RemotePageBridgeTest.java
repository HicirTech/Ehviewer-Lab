/*
 * Copyright 2026 Ehviewer SMB Saver fork
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.hippo.ehviewer.spider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.storage.NetworkStorage;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;
import com.hippo.unifile.UniFile;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The bridge publishes through pipes that rename-on-close whether the copy finished or not; a
 * failed copy must therefore take the just-published name back off the share (#150), or the
 * truncated page reads as saved forever.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class,
        shadows = {RemotePageBridgeTest.ShadowSpiderDen.class},
        instrumentedPackages = {"com.hippo.ehviewer.spider"})
public class RemotePageBridgeTest {

    static UniFile phoneDir;

    @Implements(SpiderDen.class)
    public static class ShadowSpiderDen {
        @Implementation
        protected static UniFile getExistingGalleryDownloadDir(GalleryInfo info) {
            return phoneDir;
        }
    }

    /** Records the calls; its output stream optionally dies mid-copy. */
    private static final class RecordingStorage implements GallerySpiderStorage {
        final List<String> calls = new ArrayList<>();
        boolean writeFails;

        @Override
        public boolean prepareDir() {
            return true;
        }

        @Override
        public InputStream openSpiderInfoInputStream() {
            return null;
        }

        @Override
        public OutputStream openSpiderInfoOutputStream() {
            return null;
        }

        @Override
        public boolean containImage(int index) {
            return false;
        }

        @Override
        public boolean removeImage(int index) {
            calls.add("remove:" + index);
            return true;
        }

        @Override
        public OutputStreamPipe openImageOutputStreamPipe(int index, String extension) {
            calls.add("open:" + index + extension);
            return new OutputStreamPipe() {
                @Override public void obtain() {}

                @Override public void release() {}

                @Override
                public OutputStream open() {
                    return new OutputStream() {
                        private int written;

                        @Override
                        public void write(int b) throws IOException {
                            if (writeFails && ++written > 2) {
                                throw new IOException("share went away mid-copy");
                            }
                        }
                    };
                }

                @Override
                public void close() {
                    calls.add("close:" + index);
                }
            };
        }

        @Override
        public InputStreamPipe openImageInputStreamPipe(int index) {
            return null;
        }
    }

    private RecordingStorage storage;
    private final GalleryInfo gallery = NetworkStorage.lookupKey(42L, "Answer");

    @Before
    public void setUp() throws Exception {
        storage = new RecordingStorage();
        File dir = Files.createTempDirectory("bridge-test").toFile();
        Files.write(new File(dir, SpiderDen.generateImageFilename(0, ".jpg")).toPath(),
                "page bytes".getBytes());
        phoneDir = UniFile.fromFile(dir);
    }

    /** The failed copy's publish is rolled back through the same seam it went in by. */
    @Test
    public void aFailedPhoneCopyTakesThePublishedNameBackOff() {
        storage.writeFails = true;
        RemotePageBridge bridge = new RemotePageBridge(gallery, gallery.gid);

        assertFalse(bridge.copyFromPhone(0, storage));

        assertTrue("the partial publish must be deleted: " + storage.calls,
                storage.calls.contains("remove:0"));
        assertTrue("delete must come after the publishing close",
                storage.calls.indexOf("close:0") < storage.calls.indexOf("remove:0"));
    }

    /** A finished copy is left alone. */
    @Test
    public void aSuccessfulPhoneCopyIsNotDeleted() {
        RemotePageBridge bridge = new RemotePageBridge(gallery, gallery.gid);

        assertTrue(bridge.copyFromPhone(0, storage));

        assertEquals("no removeImage for a good copy: " + storage.calls,
                -1, storage.calls.indexOf("remove:0"));
    }
}
