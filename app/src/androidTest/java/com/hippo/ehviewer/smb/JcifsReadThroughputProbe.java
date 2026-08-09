package com.hippo.ehviewer.smb;

import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.hippo.ehviewer.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * Spike round 3. Round 2 measured SMB reads at about 1 MB/s while writes ran at 5 MB/s, which is
 * not a plausible number for this link — so the measurement is suspect, not the network.
 *
 * <p>The likely culprit is the read loop, not the transport. Round 2 read through a
 * {@code byte[8192]}, and if jcifs turns each of those into its own SMB2 READ then a megabyte costs
 * 128 serialized round trips. Writes looked fast because they went out as one big call.
 *
 * <p>That matters well beyond the probe: {@code SmbStorage.readAll} uses the same 8 KB chunk, so if
 * this is the cause it is a live defect on the app's own read path, not an artefact.
 *
 * <p>This round varies one thing at a time — caller buffer size, a BufferedInputStream in front of
 * a small loop, and jcifs' own {@code rcv_buf_size} — against the same 4 MB file.
 */
@RunWith(AndroidJUnit4.class)
public class JcifsReadThroughputProbe {

    private static final String TAG = "JcifsProbe3";
    private static final int FILE_BYTES = 4 * 1024 * 1024;

    private CIFSContext ctx;
    private String scratchUrl;
    private String user;
    private String pass;
    private boolean signingDisabled;

    @Test
    public void probe() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(target);

        String host = p.getString(Settings.KEY_SMB_HOST, "");
        String port = p.getString(Settings.KEY_SMB_PORT, "445");
        String share = p.getString(Settings.KEY_SMB_SHARE_NAME, "");
        String path = normalisePath(p.getString(Settings.KEY_SMB_SHARE_PATH, "/"));
        user = p.getString(Settings.KEY_SMB_USERNAME, "");
        pass = p.getString(Settings.KEY_SMB_PASSWORD, "");
        signingDisabled = p.getBoolean(Settings.KEY_SMB_SIGNING_DISABLED, false);

        if (isEmpty(host) || isEmpty(share)) {
            fail("No SMB share configured on this device.");
        }

        ctx = buildContext(null);
        scratchUrl = buildShareUrl(host, port, share, path) + ".jcifs-probe3/";

        SmbFile scratch = new SmbFile(scratchUrl, ctx);
        if (scratch.exists()) deleteRecursive(scratch);
        scratch.mkdirs();

        try {
            // One 4 MB file, read every way. Same bytes each time, so only the read strategy varies.
            byte[] payload = new byte[FILE_BYTES];
            Arrays.fill(payload, (byte) 'z');
            long t0 = System.nanoTime();
            try (OutputStream os = new SmbFile(scratchUrl + "big.bin", ctx).getOutputStream()) {
                os.write(payload);
            }
            line("upload of " + (FILE_BYTES / 1024 / 1024) + " MB in one write() call: "
                    + (System.nanoTime() - t0) / 1_000_000L + "ms");
            line("");

            line("--- caller buffer size, default jcifs config ---");
            for (int kb : new int[]{8, 32, 64, 128, 256, 1024}) {
                measure("plain loop, byte[" + kb + " KB]", ctx, kb * 1024, false);
            }

            line("");
            line("--- BufferedInputStream in front of a small loop ---");
            measure("BufferedInputStream(1 MB) + byte[8 KB] loop", ctx, 8 * 1024, true);

            line("");
            line("--- jcifs rcv_buf_size raised, caller buffer held at 8 KB ---");
            for (int kb : new int[]{64, 256, 1024}) {
                CIFSContext tuned = buildContext(kb * 1024);
                measure("rcv_buf_size=" + kb + " KB, byte[8 KB]", tuned, 8 * 1024, false);
            }

            line("");
            line("--- both raised ---");
            CIFSContext tuned = buildContext(1024 * 1024);
            measure("rcv_buf_size=1 MB, byte[1 MB]", tuned, 1024 * 1024, false);
        } finally {
            try {
                deleteRecursive(new SmbFile(scratchUrl, ctx));
            } catch (Throwable e) {
                line("[warn] cleanup failed: " + e);
            }
        }
    }

    private void measure(String label, CIFSContext c, int bufBytes, boolean buffered) {
        try {
            long best = Long.MAX_VALUE;
            long total = 0;
            int runs = 3;
            for (int i = 0; i < runs; i++) {
                long t0 = System.nanoTime();
                int read = 0;
                InputStream is = new SmbFile(scratchUrl + "big.bin", c).getInputStream();
                if (buffered) {
                    is = new BufferedInputStream(is, 1024 * 1024);
                }
                try {
                    byte[] buf = new byte[bufBytes];
                    int n;
                    while ((n = is.read(buf)) != -1) read += n;
                } finally {
                    try {
                        is.close();
                    } catch (Throwable ignored) {
                    }
                }
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (read != FILE_BYTES) {
                    line(pad(label) + "SHORT READ " + read + "/" + FILE_BYTES);
                    return;
                }
                best = Math.min(best, ms);
                total += ms;
            }
            long avg = total / runs;
            double mbps = FILE_BYTES / 1024.0 / 1024.0 / (best / 1000.0);
            line(pad(label) + "best=" + best + "ms avg=" + avg + "ms  "
                    + String.format("%.1f", mbps) + " MB/s");
        } catch (Throwable e) {
            line(pad(label) + "EXCEPTION " + e);
        }
    }

    private static String pad(String s) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < 44) b.append(' ');
        return b + " : ";
    }

    /** @param rcvBufSize null keeps the app's own configuration untouched. */
    private CIFSContext buildContext(Integer rcvBufSize) throws Exception {
        CIFSContext base;
        if (signingDisabled || rcvBufSize != null) {
            Properties props = new Properties();
            if (signingDisabled) {
                props.setProperty("jcifs.smb.client.signingPreferred", "false");
                props.setProperty("jcifs.smb.client.signingEnforced", "false");
                props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
            }
            if (rcvBufSize != null) {
                props.setProperty("jcifs.smb.client.rcv_buf_size", String.valueOf(rcvBufSize));
                props.setProperty("jcifs.smb.client.snd_buf_size", String.valueOf(rcvBufSize));
            }
            base = new BaseContext(new PropertyConfiguration(props));
        } else {
            base = SingletonContext.getInstance();
        }
        return isEmpty(user) ? base : base.withCredentials(new NtlmPasswordAuthenticator(null, user, pass));
    }

    private String buildShareUrl(String host, String port, String share, String path) throws Exception {
        StringBuilder url = new StringBuilder("smb://").append(host.trim());
        String p = port == null ? "445" : port.trim();
        if (!p.isEmpty() && !p.equals("445")) url.append(":").append(p);
        url.append("/").append(URLEncoder.encode(share.trim(), "UTF-8").replace("+", "%20"));
        return url.append(path).toString();
    }

    private static String normalisePath(String raw) {
        String v = raw == null ? "/" : raw.trim();
        if (!v.startsWith("/")) v = "/" + v;
        if (!v.endsWith("/")) v = v + "/";
        return v;
    }

    private static void deleteRecursive(SmbFile f) throws Exception {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            SmbFile[] kids = f.listFiles();
            if (kids != null) {
                for (SmbFile k : kids) {
                    try {
                        deleteRecursive(k);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        f.delete();
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void line(String s) {
        Log.i(TAG, s);
    }
}
