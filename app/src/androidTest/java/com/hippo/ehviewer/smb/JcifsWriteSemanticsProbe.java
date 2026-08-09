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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

/**
 * Spike, not a regression test — it talks to whatever real share this device is configured for and
 * is not runnable in CI. It exists to answer the questions blocking the share-side index (#16) and
 * the shared download state (#59).
 *
 * <p>An earlier round of these measurements went through the kernel CIFS client on a wired host.
 * Neither half of that transfers: the app links against jcifs-ng, and it runs over this tablet's
 * WiFi. Both the verdicts and — especially — the latency numbers have to come from here.
 *
 * <p>Credentials are never handled by hand: the probe reads the share this app is already
 * configured for straight out of its own preferences, and connects exactly the way
 * {@code SmbStorage.buildContext} does, honouring the same signing switch.
 *
 * <p>Everything is written inside a {@code .jcifs-probe/} scratch folder at the share root, which
 * is removed on the way out. Results go to logcat under {@code JcifsProbe} and to a report file in
 * the app's external files dir.
 *
 * <pre>
 *   ./gradlew connectedAppReleaseDebugAndroidTest
 *   adb logcat -d -s JcifsProbe
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
public class JcifsWriteSemanticsProbe {

    private static final String TAG = "JcifsProbe";

    private CIFSContext ctx;
    private String shareUrl;
    private String scratchUrl;

    private final List<String> report = new ArrayList<>();
    private int failures = 0;

    @Test
    public void probe() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(target);

        String host = prefs.getString(Settings.KEY_SMB_HOST, "");
        String port = prefs.getString(Settings.KEY_SMB_PORT, "445");
        String share = prefs.getString(Settings.KEY_SMB_SHARE_NAME, "");
        String path = normalisePath(prefs.getString(Settings.KEY_SMB_SHARE_PATH, "/"));
        String user = prefs.getString(Settings.KEY_SMB_USERNAME, "");
        String pass = prefs.getString(Settings.KEY_SMB_PASSWORD, "");
        boolean signingDisabled = prefs.getBoolean(Settings.KEY_SMB_SIGNING_DISABLED, false);

        if (isEmpty(host) || isEmpty(share)) {
            fail("This device has no SMB share configured — set it up in the app first. "
                    + "host=" + (isEmpty(host) ? "<empty>" : "set")
                    + " share=" + (isEmpty(share) ? "<empty>" : "set"));
        }

        ctx = buildContext(user, pass, signingDisabled);
        shareUrl = buildShareUrl(host, port, share, path);
        scratchUrl = shareUrl + ".jcifs-probe/";

        // Deliberately does not log host/share/user — only whether credentials were present.
        line("device : " + android.os.Build.MODEL + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        line("signing: " + (signingDisabled ? "disabled" : "default"));
        line("creds  : " + (isEmpty(user) ? "anonymous" : "user+password set"));
        line("");

        SmbFile scratch = new SmbFile(scratchUrl, ctx);
        if (scratch.exists()) {
            deleteRecursive(scratch);
        }
        scratch.mkdirs();

        try {
            t1FileRenameOverwrite();
            t2DirRename();
            t3DirRenameOntoExisting();
            t4RenameWhileReaderHoldsHandle();
            t5CreateNewFileMutualExclusion();
            t6LockMtime();
            t7RoundTripLatency();
            t8ConcurrentAtomicOverwrite();
            t9StaleLockTakeover();
            t10ShareRootListing();
        } finally {
            try {
                deleteRecursive(new SmbFile(scratchUrl, ctx));
            } catch (Throwable e) {
                line("[warn] scratch cleanup failed: " + e);
            }
            writeReport(target);
        }

        if (failures > 0) {
            fail(failures + " probe check(s) failed — see the JcifsProbe log. A failure here is a"
                    + " finding, not a broken test: it means the design resting on that assumption"
                    + " has to change.");
        }
    }

    // ------------------------------------------------------------------ semantics

    /**
     * The atomic-write primitive. If a rename cannot land on top of a live file, the write has to
     * delete first, and that leaves a window where a concurrent reader finds nothing at all.
     */
    private void t1FileRenameOverwrite() {
        String name = "T1 file renameTo over an existing file";
        try {
            SmbFile target = file("t1-target.json");
            write(target, "OLD");

            String oneArg;
            try {
                SmbFile tmp = file("t1-tmp.json");
                write(tmp, "NEW");
                tmp.renameTo(file("t1-target.json"));
                oneArg = "renameTo(dest)=OK";
            } catch (Throwable e) {
                oneArg = "renameTo(dest) threw " + e.getClass().getSimpleName();
            }

            // The two-arg overload maps to SMB2 SET_INFO with ReplaceIfExists.
            String twoArg;
            try {
                SmbFile tmp2 = file("t1-tmp2.json");
                write(tmp2, "NEW2");
                tmp2.renameTo(file("t1-target.json"), true);
                twoArg = "renameTo(dest,true)=OK";
            } catch (Throwable e) {
                twoArg = "renameTo(dest,true) threw " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            String content = read(file("t1-target.json"));
            record(name, twoArg.endsWith("OK") && "NEW2".equals(content),
                    oneArg + " | " + twoArg + " | final=" + content);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** #16's resync renames the gallery folder when the online title changes. */
    private void t2DirRename() {
        String name = "T2 directory renameTo, with contents";
        try {
            SmbFile a = dir("t2-a");
            a.mkdirs();
            write(new SmbFile(a, "inner.txt"), "payload");
            new SmbFile(scratchUrl + "t2-a/", ctx).renameTo(dir("t2-b"));

            boolean gone = !dir("t2-a").exists();
            boolean there = dir("t2-b").exists();
            boolean intact = there && "payload".equals(read(new SmbFile(dir("t2-b"), "inner.txt")));
            record(name, gone && there && intact,
                    "old gone=" + gone + " new exists=" + there + " contents intact=" + intact);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** A name collision must be detectable, never a silent merge or clobber. */
    private void t3DirRenameOntoExisting() {
        String name = "T3 directory renameTo onto an EXISTING dir (expect failure)";
        try {
            SmbFile src = dir("t3-src");
            src.mkdirs();
            write(new SmbFile(src, "s.txt"), "src");
            SmbFile dst = dir("t3-dst");
            dst.mkdirs();
            write(new SmbFile(dst, "d.txt"), "dst");

            boolean ok;
            String outcome;
            try {
                new SmbFile(scratchUrl + "t3-src/", ctx).renameTo(dir("t3-dst"));
                outcome = "SUCCEEDED — collision is silent, rename must pre-check";
                ok = false;
            } catch (Throwable e) {
                outcome = "threw " + e.getClass().getSimpleName() + " — collision is detectable";
                ok = true;
            }
            record(name, ok, outcome);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /**
     * Another client can be reading a page out of the folder while we rename it. Does its open
     * handle survive, and does it still deliver every byte?
     */
    private void t4RenameWhileReaderHoldsHandle() {
        String name = "T4 rename a dir while a reader holds an open handle inside it";
        try {
            SmbFile a = dir("t4-a");
            a.mkdirs();
            byte[] payload = new byte[512 * 1024];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 251);
            }
            try (OutputStream os = new SmbFile(a, "big.bin").getOutputStream()) {
                os.write(payload);
            }

            InputStream is = new SmbFile(scratchUrl + "t4-a/big.bin", ctx).getInputStream();
            int head = readFully(is, new byte[4096]);

            String renamed;
            try {
                new SmbFile(scratchUrl + "t4-a/", ctx).renameTo(dir("t4-b"));
                renamed = "rename OK";
            } catch (Throwable e) {
                renamed = "rename threw " + e.getClass().getSimpleName();
            }

            int rest = 0;
            String readOutcome;
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) rest += n;
                readOutcome = "reader got " + (head + rest) + "/" + payload.length + " bytes";
            } catch (Throwable e) {
                readOutcome = "reader threw " + e.getClass().getSimpleName() + " after " + (head + rest) + " bytes";
            } finally {
                close(is);
            }

            record(name, renamed.endsWith("OK") && head + rest == payload.length,
                    renamed + " | " + readOutcome);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** The lock primitive: exactly one of N concurrent creators must win. */
    private void t5CreateNewFileMutualExclusion() {
        String name = "T5 createNewFile as a mutex, 8 concurrent creators";
        try {
            SmbFile lock = file("t5.lock");
            if (lock.exists()) lock.delete();

            int n = 8;
            AtomicInteger winners = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            AtomicInteger other = new AtomicInteger();
            runConcurrently(n, () -> {
                try {
                    new SmbFile(scratchUrl + "t5.lock", ctx).createNewFile();
                    winners.incrementAndGet();
                } catch (SmbException e) {
                    refused.incrementAndGet();
                } catch (Throwable e) {
                    other.incrementAndGet();
                }
            });

            record(name, winners.get() == 1 && other.get() == 0,
                    "winners=" + winners.get() + " refused=" + refused.get() + " other-errors=" + other.get()
                            + (winners.get() > 1 ? "  <-- NOT a mutex" : ""));
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** Stale-lock reclamation and the download-state heartbeat both key off mtime. */
    private void t6LockMtime() {
        String name = "T6 lock mtime readable, and its resolution";
        try {
            SmbFile lock = file("t6.lock");
            if (lock.exists()) lock.delete();
            long localBefore = System.currentTimeMillis();
            lock.createNewFile();
            long m1 = file("t6.lock").lastModified();

            Thread.sleep(1500);
            write(file("t6.lock"), "touch");
            long m2 = file("t6.lock").lastModified();

            record(name, m1 > 0 && m2 > m1,
                    "mtime=" + m1 + " server-vs-device clock skew=" + (m1 - localBefore) + "ms"
                            + " | delta after 1500ms sleep=" + (m2 - m1) + "ms");
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    // ------------------------------------------------------------------ numbers

    /**
     * The number that shapes both designs. One round is a complete critical section: take the
     * lock, read the state, mutate it, write a temp, rename it over, drop the lock.
     *
     * <p>1 KB stands in for a small download-state file, 1 MB for the full-fat plaintext index at
     * roughly a thousand galleries.
     */
    private void t7RoundTripLatency() {
        for (int kb : new int[]{1, 100, 1024}) {
            String name = "T7 full critical section (lock/read/mutate/write/rename/unlock) @ " + kb + " KB";
            try {
                String stateName = "t7-" + kb + ".json";
                write(file(stateName), filled('p', kb * 1024));

                int rounds = 15;
                long[] ms = new long[rounds];
                for (int i = 0; i < rounds; i++) {
                    long t0 = System.nanoTime();

                    SmbFile lock = file("t7-" + kb + ".lock");
                    lock.createNewFile();
                    String cur = read(file(stateName));
                    String next = cur.substring(0, cur.length() - 1) + "x";
                    SmbFile tmp = file("t7-" + kb + ".tmp");
                    write(tmp, next);
                    tmp.renameTo(file(stateName), true);
                    lock.delete();

                    ms[i] = (System.nanoTime() - t0) / 1_000_000L;
                }
                Arrays.sort(ms);
                record(name, true, "p50=" + ms[rounds / 2] + "ms  p95=" + ms[(int) (rounds * 0.95)]
                        + "ms  min=" + ms[0] + "ms  max=" + ms[rounds - 1] + "ms");
            } catch (Throwable e) {
                record(name, false, "EXCEPTION " + e);
            }
        }
    }

    /** A reader must never observe a half-written state file. */
    private void t8ConcurrentAtomicOverwrite() {
        String name = "T8 concurrent tmp+rename never yields a torn or missing read";
        try {
            int size = 64 * 1024;
            write(file("t8.json"), filled('A', size));

            AtomicInteger torn = new AtomicInteger();
            AtomicInteger missing = new AtomicInteger();
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger writeErrors = new AtomicInteger();
            AtomicBoolean running = new AtomicBoolean(true);

            Thread reader = new Thread(() -> {
                while (running.get()) {
                    try {
                        String s = read(file("t8.json"));
                        reads.incrementAndGet();
                        if (s.length() != size || s.chars().distinct().count() != 1) {
                            torn.incrementAndGet();
                        }
                    } catch (Throwable e) {
                        missing.incrementAndGet();
                    }
                }
            }, "t8-reader");
            reader.start();

            int writers = 4;
            CountDownLatch done = new CountDownLatch(writers);
            for (int i = 0; i < writers; i++) {
                final char c = (char) ('A' + i);
                new Thread(() -> {
                    try {
                        for (int r = 0; r < 5; r++) {
                            SmbFile tmp = file("t8-" + c + ".tmp");
                            write(tmp, filled(c, size));
                            tmp.renameTo(file("t8.json"), true);
                        }
                    } catch (Throwable e) {
                        writeErrors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }, "t8-w" + c).start();
            }
            done.await();
            running.set(false);
            reader.join();

            record(name, torn.get() == 0 && missing.get() == 0 && writeErrors.get() == 0,
                    "reads=" + reads.get() + " torn=" + torn.get() + " missing=" + missing.get()
                            + " write-errors=" + writeErrors.get());
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** A client that died holding the lock must not wedge the share for everyone else. */
    private void t9StaleLockTakeover() {
        String name = "T9 stale-lock takeover: naive delete-then-create, 4 racers";
        try {
            SmbFile lock = file("t9.lock");
            if (lock.exists()) lock.delete();
            lock.createNewFile();   // the owner that crashed

            AtomicInteger winners = new AtomicInteger();
            AtomicInteger losers = new AtomicInteger();
            runConcurrently(4, () -> {
                try {
                    file("t9.lock").delete();
                } catch (Throwable ignored) {
                }
                try {
                    file("t9.lock").createNewFile();
                    winners.incrementAndGet();
                } catch (Throwable e) {
                    losers.incrementAndGet();
                }
            });

            record(name, winners.get() == 1, "winners=" + winners.get() + " losers=" + losers.get()
                    + (winners.get() > 1
                    ? "  <-- naive takeover is UNSAFE, needs a rename-based or two-phase protocol"
                    : ""));
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** The authoritative half of the three-way reconcile — this cost is paid on every refresh. */
    private void t10ShareRootListing() {
        String name = "T10 share-root listFiles() + isDirectory/createTime walk";
        try {
            long t0 = System.nanoTime();
            SmbFile[] children = new SmbFile(shareUrl, ctx).listFiles();
            long listMs = (System.nanoTime() - t0) / 1_000_000L;

            int dirs = 0;
            long t1 = System.nanoTime();
            for (SmbFile c : children) {
                if (c.isDirectory()) {
                    dirs++;
                    c.createTime();
                }
            }
            long walkMs = (System.nanoTime() - t1) / 1_000_000L;

            record(name, true, children.length + " entries, " + dirs + " dirs | listFiles=" + listMs
                    + "ms | attribute walk=" + walkMs + "ms");
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Mirrors {@code SmbStorage.buildContext} so the probe connects exactly like the app. */
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

    /** Mirrors {@code SmbPaths.buildShareUrl}. */
    private String buildShareUrl(String host, String port, String share, String path) throws Exception {
        StringBuilder url = new StringBuilder("smb://").append(host.trim());
        String p = port == null ? "445" : port.trim();
        if (!p.isEmpty() && !p.equals("445")) {
            url.append(":").append(p);
        }
        url.append("/").append(URLEncoder.encode(share.trim(), "UTF-8").replace("+", "%20"));
        url.append(path);
        return url.toString();
    }

    private static String normalisePath(String raw) {
        String v = raw == null ? "/" : raw.trim();
        if (!v.startsWith("/")) v = "/" + v;
        if (!v.endsWith("/")) v = v + "/";
        return v;
    }

    private void runConcurrently(int n, Runnable body) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    body.run();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "probe-" + i).start();
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

    private static int readFully(InputStream is, byte[] buf) throws Exception {
        int total = 0;
        while (total < buf.length) {
            int n = is.read(buf, total, buf.length - total);
            if (n == -1) break;
            total += n;
        }
        return total;
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

    private void writeReport(Context context) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return;
            File out = new File(dir, "jcifs-probe-report.txt");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                for (String s : report) {
                    fos.write((s + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            Log.i(TAG, "report written to " + out.getAbsolutePath());
        } catch (Throwable e) {
            Log.w(TAG, "could not write report file", e);
        }
    }
}
