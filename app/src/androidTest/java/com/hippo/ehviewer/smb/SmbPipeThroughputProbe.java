package com.hippo.ehviewer.smb;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Verification for the SMB buffer fix. Drives the real production entry point rather than a
 * hand-rolled loop, and writes the way {@code SpiderQueen:1482} does — a {@code byte[4096]} at a
 * time — so the number this reports is the number the download path gets.
 *
 * <p>Baseline for comparison, measured against a raw {@code SmbFileOutputStream} on the same
 * device and share before the fix: <b>0.5 MB/s</b>.
 */
@RunWith(AndroidJUnit4.class)
public class SmbPipeThroughputProbe {

    private static final String TAG = "SmbPipeProbe";
    private static final int BYTES = 4 * 1024 * 1024;
    private static final long PROBE_GID = 999000001L;

    @Test
    public void writeThroughputThroughProductionPipe() throws Exception {
        if (!SmbStorage.isConfigured()) {
            fail("No SMB share configured on this device.");
        }

        GalleryInfo info = new GalleryInfo();
        info.gid = PROBE_GID;
        info.title = "smb-buffer-probe";
        info.pages = 1;

        byte[] payload = new byte[BYTES];
        Arrays.fill(payload, (byte) 'w');

        try {
            OutputStreamPipe pipe = SmbStorage.openSmbOutputStreamPipe(info, 0, ".jpg");
            assertNotNull("production pipe was null — share unreachable?", pipe);

            long t0 = System.nanoTime();
            pipe.obtain();
            try {
                OutputStream os = pipe.open();
                // Exactly SpiderQueen's chunk size.
                byte[] chunk = new byte[4096];
                for (int off = 0; off < payload.length; off += chunk.length) {
                    int n = Math.min(chunk.length, payload.length - off);
                    System.arraycopy(payload, off, chunk, 0, n);
                    os.write(chunk, 0, n);
                }
            } finally {
                pipe.close();
                pipe.release();
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            double mbps = BYTES / 1024.0 / 1024.0 / (ms / 1000.0);

            Log.i(TAG, "SmbStorage.openSmbOutputStreamPipe, 4 MB written in byte[4 KB] chunks: "
                    + ms + "ms  " + String.format("%.1f", mbps) + " MB/s   (pre-fix baseline 0.5 MB/s)");

            if (mbps < 3.0) {
                fail("throughput " + String.format("%.1f", mbps)
                        + " MB/s — the buffer fix is not reaching the production path");
            }

            // The real hazard in this change is a buffer that never gets flushed, which would
            // silently truncate every page written to the share. Read the file back and count.
            InputStreamPipe in = SmbStorage.openSmbInputStreamPipe(info, 0);
            assertNotNull("could not reopen what we just wrote", in);
            long readBack = 0;
            in.obtain();
            try (InputStream is = in.open()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) != -1) {
                    readBack += n;
                    for (int i = 0; i < n; i++) {
                        if (buf[i] != 'w') {
                            fail("content corrupted at byte " + (readBack - n + i));
                        }
                    }
                }
            } finally {
                in.close();
                in.release();
            }
            Log.i(TAG, "read back " + readBack + "/" + BYTES + " bytes");
            if (readBack != BYTES) {
                fail("TRUNCATED: wrote " + BYTES + " bytes, read back " + readBack
                        + " — the buffered stream is not being flushed on close");
            }
        } finally {
            SmbStorage.deleteGalleryFolder(info);
        }
    }
}
