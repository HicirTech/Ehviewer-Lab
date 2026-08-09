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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

/**
 * Spike round 2. Round 1 ({@link JcifsWriteSemanticsProbe}) produced four findings that knock out
 * the design the share-side index (#16) and the shared download state (#59) were resting on:
 *
 * <ul>
 *   <li>{@code createNewFile()} is <b>not</b> a mutex — 8 concurrent creators all won.</li>
 *   <li>The one-arg {@code renameTo(dest)} refuses to land on an existing name. Round 1 recorded
 *       that as a limitation; it is actually the exclusive primitive we need.</li>
 *   <li>Renaming a directory fails while another handle is open inside it.</li>
 *   <li>Renaming over a file fails while a reader has it open, which is what really broke the
 *       round-1 concurrency check.</li>
 * </ul>
 *
 * This round tests the replacement primitives and pins down the exact NT statuses, plus the
 * read-only cost that the round-1 numbers folded into a full read-modify-write.
 */
@RunWith(AndroidJUnit4.class)
public class JcifsLockPrimitiveProbe {

    private static final String TAG = "JcifsProbe2";

    private CIFSContext ctx;
    private String scratchUrl;
    private final List<String> report = new ArrayList<>();
    private int failures = 0;

    @Test
    public void probe() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(target);

        String host = p.getString(Settings.KEY_SMB_HOST, "");
        String port = p.getString(Settings.KEY_SMB_PORT, "445");
        String share = p.getString(Settings.KEY_SMB_SHARE_NAME, "");
        String path = normalisePath(p.getString(Settings.KEY_SMB_SHARE_PATH, "/"));
        String user = p.getString(Settings.KEY_SMB_USERNAME, "");
        String pass = p.getString(Settings.KEY_SMB_PASSWORD, "");
        boolean signingDisabled = p.getBoolean(Settings.KEY_SMB_SIGNING_DISABLED, false);

        if (isEmpty(host) || isEmpty(share)) {
            fail("No SMB share configured on this device.");
        }

        ctx = buildContext(user, pass, signingDisabled);
        scratchUrl = buildShareUrl(host, port, share, path) + ".jcifs-probe2/";

        SmbFile scratch = new SmbFile(scratchUrl, ctx);
        if (scratch.exists()) deleteRecursive(scratch);
        scratch.mkdirs();

        try {
            r1RenameAsMutex();
            r2MkdirAsMutex();
            r3NtStatusDirRenameWithOpenHandle();
            r4NtStatusFileRenameOverOpenReader();
            r5RetryUntilReaderCloses();
            r6ReadOnlyLatency();
            r7WriteOnlyLatency();
            r8LargeDirectoryListing();
        } finally {
            try {
                deleteRecursive(new SmbFile(scratchUrl, ctx));
            } catch (Throwable e) {
                line("[warn] cleanup failed: " + e);
            }
        }

        if (failures > 0) {
            fail(failures + " check(s) failed — see the JcifsProbe2 log.");
        }
    }

    // ---------------------------------------------------------------- replacement primitives

    /**
     * The candidate mutex: write a file under a name only this client uses, then rename it onto
     * the shared lock name. Round 1 proved that rename refuses an existing target, so the rename
     * should be the thing that arbitrates.
     */
    private void r1RenameAsMutex() {
        String name = "R1 rename-onto-shared-name as a mutex, 8 racers";
        try {
            SmbFile lock = file("r1.lock");
            if (lock.exists()) lock.delete();

            AtomicInteger winners = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            AtomicReference<String> refusalStatus = new AtomicReference<>("");
            runConcurrently(8, i -> {
                try {
                    SmbFile mine = file("r1-claim-" + i);
                    write(mine, "owner=" + i);
                    mine.renameTo(file("r1.lock"));
                    winners.incrementAndGet();
                } catch (SmbException e) {
                    refused.incrementAndGet();
                    refusalStatus.compareAndSet("", ntStatus(e));
                } catch (Throwable e) {
                    refusalStatus.compareAndSet("", "non-Smb: " + e);
                }
            });

            String owner = "";
            try {
                owner = read(file("r1.lock"));
            } catch (Throwable ignored) {
            }
            record(name, winners.get() == 1,
                    "winners=" + winners.get() + " refused=" + refused.get()
                            + " | refusal=" + refusalStatus.get() + " | lock contains: " + owner);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** The classic alternative: mkdir fails when the directory already exists. */
    private void r2MkdirAsMutex() {
        String name = "R2 mkdir as a mutex, 8 racers";
        try {
            SmbFile lock = dir("r2-lock");
            if (lock.exists()) lock.delete();

            AtomicInteger winners = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            AtomicReference<String> status = new AtomicReference<>("");
            runConcurrently(8, i -> {
                try {
                    dir("r2-lock").mkdir();
                    winners.incrementAndGet();
                } catch (SmbException e) {
                    refused.incrementAndGet();
                    status.compareAndSet("", ntStatus(e));
                } catch (Throwable e) {
                    status.compareAndSet("", "non-Smb: " + e);
                }
            });
            record(name, winners.get() == 1,
                    "winners=" + winners.get() + " refused=" + refused.get() + " | refusal=" + status.get());
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    // ---------------------------------------------------------------- exact failure modes

    /** Round 1 saw SmbAuthException here. Which NT status is actually behind it? */
    private void r3NtStatusDirRenameWithOpenHandle() {
        String name = "R3 exact status: rename a dir while a handle is open inside it";
        try {
            SmbFile a = dir("r3-a");
            a.mkdirs();
            write(new SmbFile(a, "inner.bin"), filled('x', 128 * 1024));

            InputStream is = new SmbFile(scratchUrl + "r3-a/inner.bin", ctx).getInputStream();
            is.read(new byte[1024]);

            String withHandle;
            try {
                new SmbFile(scratchUrl + "r3-a/", ctx).renameTo(dir("r3-b"));
                withHandle = "SUCCEEDED (round 1 was a fluke)";
            } catch (SmbException e) {
                withHandle = e.getClass().getSimpleName() + " " + ntStatus(e);
            } catch (Throwable e) {
                withHandle = "non-Smb: " + e;
            }
            close(is);

            String afterClose;
            try {
                new SmbFile(scratchUrl + "r3-a/", ctx).renameTo(dir("r3-b"));
                afterClose = "OK once the handle is closed";
            } catch (Throwable e) {
                afterClose = "STILL fails: " + e.getClass().getSimpleName();
            }

            record(name, afterClose.startsWith("OK"), "with open handle: " + withHandle
                    + " | after close: " + afterClose);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** The real cause of the round-1 concurrency failure, isolated. */
    private void r4NtStatusFileRenameOverOpenReader() {
        String name = "R4 exact status: renameTo(dest,true) while a reader holds dest open";
        try {
            write(file("r4-target.json"), filled('A', 32 * 1024));
            write(file("r4-tmp.json"), filled('B', 32 * 1024));

            InputStream is = file("r4-target.json").getInputStream();
            is.read(new byte[1024]);

            String withReader;
            try {
                file("r4-tmp.json").renameTo(file("r4-target.json"), true);
                withReader = "SUCCEEDED — an open reader does NOT block the swap";
            } catch (SmbException e) {
                withReader = e.getClass().getSimpleName() + " " + ntStatus(e);
            } catch (Throwable e) {
                withReader = "non-Smb: " + e;
            }
            close(is);

            String afterClose;
            try {
                if (!file("r4-tmp.json").exists()) write(file("r4-tmp.json"), filled('B', 32 * 1024));
                file("r4-tmp.json").renameTo(file("r4-target.json"), true);
                afterClose = "OK once the reader closed";
            } catch (Throwable e) {
                afterClose = "STILL fails: " + e.getClass().getSimpleName();
            }

            // Whichever way it goes is a finding; only a hard error after the reader is gone is a bug.
            record(name, afterClose.startsWith("OK"),
                    "with open reader: " + withReader + " | after close: " + afterClose);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /**
     * If a reader does block the swap, the writer has to retry. How long does a realistic reader
     * hold things up, and how many attempts does it take?
     */
    private void r5RetryUntilReaderCloses() {
        String name = "R5 writer retry while a reader is mid-read";
        try {
            write(file("r5-target.json"), filled('A', 256 * 1024));

            CountDownLatch readerStarted = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try (InputStream is = file("r5-target.json").getInputStream()) {
                    is.read(new byte[4096]);
                    readerStarted.countDown();
                    Thread.sleep(1500);          // simulate a slow consumer
                    byte[] buf = new byte[8192];
                    while (is.read(buf) != -1) { /* drain */ }
                } catch (Throwable ignored) {
                }
            }, "r5-reader");
            reader.start();
            readerStarted.await();

            int attempts = 0;
            long t0 = System.nanoTime();
            String outcome = "gave up";
            while (attempts < 40) {
                attempts++;
                try {
                    if (!file("r5-tmp.json").exists()) write(file("r5-tmp.json"), filled('B', 256 * 1024));
                    file("r5-tmp.json").renameTo(file("r5-target.json"), true);
                    outcome = "succeeded";
                    break;
                } catch (Throwable e) {
                    Thread.sleep(100);
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            reader.join();
            record(name, outcome.equals("succeeded"),
                    outcome + " after " + attempts + " attempt(s), " + ms + "ms");
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    // ---------------------------------------------------------------- isolated costs

    /**
     * Round 1's T7 lumped read, write, rename and locking together. The common path for the index
     * is a plain read, so that needs its own number — it is what every inventory open pays.
     */
    private void r6ReadOnlyLatency() {
        for (int kb : new int[]{100, 1024, 4096}) {
            String name = "R6 read-only latency @ " + kb + " KB (the index's common path)";
            try {
                SmbFile f = file("r6-" + kb + ".json");
                write(f, filled('r', kb * 1024));
                long[] ms = new long[10];
                for (int i = 0; i < ms.length; i++) {
                    long t0 = System.nanoTime();
                    read(file("r6-" + kb + ".json"));
                    ms[i] = (System.nanoTime() - t0) / 1_000_000L;
                }
                Arrays.sort(ms);
                record(name, true, "p50=" + ms[5] + "ms min=" + ms[0] + "ms max=" + ms[9]
                        + "ms  (~" + (kb * 1024L * 1000 / Math.max(1, ms[5])) / 1024 / 1024 + " MB/s)");
            } catch (Throwable e) {
                record(name, false, "EXCEPTION " + e);
            }
        }
    }

    private void r7WriteOnlyLatency() {
        for (int kb : new int[]{1, 1024}) {
            String name = "R7 write+rename latency @ " + kb + " KB (no lock, no read)";
            try {
                String payload = filled('w', kb * 1024);
                write(file("r7-" + kb + ".json"), payload);
                long[] ms = new long[10];
                for (int i = 0; i < ms.length; i++) {
                    long t0 = System.nanoTime();
                    SmbFile tmp = file("r7-" + kb + ".tmp");
                    write(tmp, payload);
                    tmp.renameTo(file("r7-" + kb + ".json"), true);
                    ms[i] = (System.nanoTime() - t0) / 1_000_000L;
                }
                Arrays.sort(ms);
                record(name, true, "p50=" + ms[5] + "ms min=" + ms[0] + "ms max=" + ms[9] + "ms");
            } catch (Throwable e) {
                record(name, false, "EXCEPTION " + e);
            }
        }
    }

    /**
     * Round 1's T10 enumerated a share with 5 folders, which says nothing about the reconcile cost
     * at the scale that motivated the index in the first place.
     */
    private void r8LargeDirectoryListing() {
        String name = "R8 enumerate 200 folders (the reconcile's authoritative half)";
        try {
            SmbFile many = dir("r8-many");
            many.mkdirs();
            long t0 = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                new SmbFile(scratchUrl + "r8-many/" + (1000000 + i) + "-gallery-" + i + "/", ctx).mkdir();
            }
            long setupMs = (System.nanoTime() - t0) / 1_000_000L;

            long t1 = System.nanoTime();
            SmbFile[] kids = new SmbFile(scratchUrl + "r8-many/", ctx).listFiles();
            long listMs = (System.nanoTime() - t1) / 1_000_000L;

            long t2 = System.nanoTime();
            int dirs = 0;
            for (SmbFile k : kids) {
                if (k.isDirectory()) {
                    dirs++;
                    k.createTime();
                }
            }
            long walkMs = (System.nanoTime() - t2) / 1_000_000L;

            record(name, true, kids.length + " entries, " + dirs + " dirs | listFiles=" + listMs
                    + "ms | isDirectory+createTime walk=" + walkMs + "ms | (setup took " + setupMs + "ms)");
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static String ntStatus(SmbException e) {
        int s = e.getNtStatus();
        return "ntStatus=0x" + Integer.toHexString(s) + " (" + e.getMessage() + ")";
    }

    private CIFSContext buildContext(String user, String pass, boolean signingDisabled) throws Exception {
        CIFSContext base;
        if (signingDisabled) {
            Properties props = new Properties();
            props.setProperty("jcifs.smb.client.signingPreferred", "false");
            props.setProperty("jcifs.smb.client.signingEnforced", "false");
            props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
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

    private interface IndexedBody {
        void run(int i);
    }

    private void runConcurrently(int n, IndexedBody body) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            new Thread(() -> {
                try {
                    start.await();
                    body.run(idx);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "probe2-" + i).start();
        }
        start.countDown();
        done.await();
    }

    private SmbFile file(String n) throws Exception {
        return new SmbFile(scratchUrl + n, ctx);
    }

    private SmbFile dir(String n) throws Exception {
        return new SmbFile(scratchUrl + n + "/", ctx);
    }

    private static void write(SmbFile f, String s) throws Exception {
        try (OutputStream os = f.getOutputStream()) {
            os.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(SmbFile f) throws Exception {
        try (InputStream is = f.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String filled(char c, int len) {
        char[] a = new char[len];
        Arrays.fill(a, c);
        return new String(a);
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

    private static void close(InputStream is) {
        try {
            is.close();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private void record(String name, boolean ok, String detail) {
        if (!ok) failures++;
        line((ok ? "PASS  " : "FAIL  ") + name);
        line("      " + detail);
    }

    private void line(String s) {
        report.add(s);
        Log.i(TAG, s);
    }
}
