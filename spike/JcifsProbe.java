/*
 * Spike probe — NOT product code, NOT meant to be merged into main.
 *
 * Answers the questions that block the share-side index (#16) and the shared download state (#59).
 * Everything here goes through jcifs-ng 2.1.10, the exact library and configuration the app uses,
 * because the earlier round of these measurements went through the kernel CIFS client and those
 * results do not transfer.
 *
 * Credentials are read from a properties file so they never end up in a transcript or in git.
 *
 *   java -cp <jcifs>:<slf4j>:<bcprov> spike/JcifsProbe.java spike/smb-probe.properties
 */

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.context.SingletonContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class JcifsProbe {

    private static CIFSContext ctx;
    private static String scratchUrl;
    private static final List<String> results = new ArrayList<>();
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        String propPath = args.length > 0 ? args[0] : "spike/smb-probe.properties";
        Properties cfg = new Properties();
        try (InputStream is = new FileInputStream(propPath)) {
            cfg.load(is);
        }

        String host = req(cfg, "host");
        String port = cfg.getProperty("port", "445");
        String share = req(cfg, "share");
        String path = cfg.getProperty("path", "/");
        String user = req(cfg, "user");
        String pass = req(cfg, "pass");
        boolean signingDisabled = Boolean.parseBoolean(cfg.getProperty("signingDisabled", "false"));

        ctx = buildContext(user, pass, signingDisabled);
        String shareUrl = buildShareUrl(host, port, share, path);
        scratchUrl = shareUrl + ".jcifs-probe/";

        System.out.println("target : " + shareUrl);
        System.out.println("signing: " + (signingDisabled ? "disabled" : "default"));
        System.out.println();

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
            t10ShareRootListing(shareUrl);
        } finally {
            try {
                deleteRecursive(new SmbFile(scratchUrl, ctx));
            } catch (Throwable e) {
                System.out.println("[warn] scratch cleanup failed: " + e);
            }
        }

        System.out.println();
        System.out.println("================ RESULTS ================");
        for (String r : results) {
            System.out.println(r);
        }
        System.out.println("=========================================");
        System.out.println(failures == 0 ? "ALL PASS" : (failures + " FAILED"));
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- tests

    /**
     * The atomic-write primitive: write a temp file, then rename it over the live one. If this
     * needs a delete first, there is a window where the file does not exist and a concurrent
     * reader sees nothing.
     */
    private static void t1FileRenameOverwrite() {
        String name = "T1 file renameTo over existing";
        try {
            SmbFile target = file("t1-target.json");
            write(target, "OLD");
            SmbFile tmp = file("t1-tmp.json");
            write(tmp, "NEW");

            String plain;
            try {
                tmp.renameTo(target);
                plain = "renameTo(dest) OK";
            } catch (Throwable e) {
                plain = "renameTo(dest) THREW " + e.getClass().getSimpleName();
            }

            // The two-arg overload asks for SMB2 ReplaceIfExists explicitly.
            if (!plain.startsWith("renameTo(dest) OK")) {
                write(tmp, "NEW");
            }
            SmbFile tmp2 = file("t1-tmp2.json");
            write(tmp2, "NEW2");
            String replace;
            try {
                tmp2.renameTo(target, true);
                replace = "renameTo(dest,true) OK";
            } catch (Throwable e) {
                replace = "renameTo(dest,true) THREW " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }

            String content = read(target);
            boolean ok = replace.endsWith("OK") && "NEW2".equals(content);
            record(name, ok, plain + " | " + replace + " | final content=" + content);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** Ticket 7 renames the gallery folder when the online title changes. */
    private static void t2DirRename() {
        String name = "T2 directory renameTo (with contents)";
        try {
            SmbFile dirA = dir("t2-a");
            dirA.mkdirs();
            write(new SmbFile(dirA, "inner.txt"), "payload");
            SmbFile dirB = dir("t2-b");
            dirA.renameTo(dirB);
            boolean ok = dirB.exists() && !dirA.exists()
                    && "payload".equals(read(new SmbFile(dirB, "inner.txt")));
            record(name, ok, "dirB exists=" + dirB.exists() + " dirA gone=" + !dirA.exists());
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** Renaming onto an existing folder must fail, not silently merge or clobber. */
    private static void t3DirRenameOntoExisting() {
        String name = "T3 directory renameTo onto EXISTING dir (expect failure)";
        try {
            SmbFile src = dir("t3-src");
            src.mkdirs();
            write(new SmbFile(src, "s.txt"), "src");
            SmbFile dst = dir("t3-dst");
            dst.mkdirs();
            write(new SmbFile(dst, "d.txt"), "dst");
            String outcome;
            boolean ok;
            try {
                src.renameTo(dst);
                outcome = "SUCCEEDED (dangerous: collision is silent)";
                ok = false;
            } catch (Throwable e) {
                outcome = "threw " + e.getClass().getSimpleName() + " (good, collision detectable)";
                ok = true;
            }
            record(name, ok, outcome);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /**
     * Another client may be reading a page out of the folder while we rename it. Does its open
     * handle survive, and does it keep returning the right bytes?
     */
    private static void t4RenameWhileReaderHoldsHandle() {
        String name = "T4 rename while a reader holds an open handle";
        try {
            SmbFile dirA = dir("t4-a");
            dirA.mkdirs();
            byte[] payload = new byte[512 * 1024];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (i % 251);
            }
            SmbFile inner = new SmbFile(dirA, "big.bin");
            try (OutputStream os = inner.getOutputStream()) {
                os.write(payload);
            }

            InputStream is = new SmbFile(dirA, "big.bin").getInputStream();
            byte[] head = is.readNBytes(4096);

            String renameOutcome;
            try {
                dirA.renameTo(dir("t4-b"));
                renameOutcome = "rename OK";
            } catch (Throwable e) {
                renameOutcome = "rename THREW " + e.getClass().getSimpleName();
            }

            String readOutcome;
            boolean contentOk = false;
            try {
                byte[] rest = is.readAllBytes();
                contentOk = head.length + rest.length == payload.length;
                readOutcome = "reader read " + (head.length + rest.length) + "/" + payload.length + " bytes";
            } catch (Throwable e) {
                readOutcome = "reader THREW " + e.getClass().getSimpleName();
            } finally {
                try { is.close(); } catch (Throwable ignored) {}
            }

            record(name, renameOutcome.endsWith("OK") && contentOk, renameOutcome + " | " + readOutcome);
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** The lock primitive. Exactly one of N concurrent creators must win. */
    private static void t5CreateNewFileMutualExclusion() {
        String name = "T5 createNewFile mutual exclusion (8 threads)";
        try {
            SmbFile lock = file("t5.lock");
            if (lock.exists()) lock.delete();

            int n = 8;
            AtomicInteger winners = new AtomicInteger();
            AtomicInteger losers = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                new Thread(() -> {
                    try {
                        start.await();
                        new SmbFile(scratchUrl + "t5.lock", ctx).createNewFile();
                        winners.incrementAndGet();
                    } catch (jcifs.smb.SmbException e) {
                        losers.incrementAndGet();
                    } catch (Throwable e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }, "t5-" + i).start();
            }
            start.countDown();
            done.await();
            boolean ok = winners.get() == 1 && errors.get() == 0;
            record(name, ok, "winners=" + winners.get() + " losers=" + losers.get() + " other-errors=" + errors.get());
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** Stale-lock reclamation and the download-state heartbeat both key off this. */
    private static void t6LockMtime() {
        String name = "T6 lock mtime readable + resolution";
        try {
            SmbFile lock = file("t6.lock");
            if (lock.exists()) lock.delete();
            long before = System.currentTimeMillis();
            lock.createNewFile();
            long m1 = new SmbFile(scratchUrl + "t6.lock", ctx).lastModified();
            long skew = m1 - before;

            Thread.sleep(1200);
            write(lock, "touch");
            long m2 = new SmbFile(scratchUrl + "t6.lock", ctx).lastModified();

            boolean ok = m1 > 0 && m2 > m1;
            record(name, ok, "mtime=" + m1 + " server-clock-skew=" + skew + "ms, delta-after-1.2s=" + (m2 - m1) + "ms");
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /**
     * THE number that decides how fine-grained shared download progress can be, and whether the
     * index and the download state can live in one file.
     * One round = acquire lock, read state, mutate, write temp, rename over, release lock.
     */
    private static void t7RoundTripLatency() throws Exception {
        for (int kb : new int[]{1, 100, 1024}) {
            String name = "T7 full lock+read+write+rename round trip @ " + kb + " KB";
            try {
                SmbFile state = file("t7-" + kb + ".json");
                write(state, payloadOf(kb));

                int rounds = 15;
                long[] samples = new long[rounds];
                for (int i = 0; i < rounds; i++) {
                    long t0 = System.nanoTime();

                    SmbFile lock = new SmbFile(scratchUrl + "t7-" + kb + ".lock", ctx);
                    lock.createNewFile();
                    String cur = read(new SmbFile(scratchUrl + "t7-" + kb + ".json", ctx));
                    String next = cur.substring(0, cur.length() - 1) + "x";
                    SmbFile tmp = new SmbFile(scratchUrl + "t7-" + kb + ".tmp", ctx);
                    write(tmp, next);
                    tmp.renameTo(new SmbFile(scratchUrl + "t7-" + kb + ".json", ctx), true);
                    lock.delete();

                    samples[i] = (System.nanoTime() - t0) / 1_000_000;
                }
                java.util.Arrays.sort(samples);
                record(name, true, "p50=" + samples[rounds / 2] + "ms p95=" + samples[(int) (rounds * 0.95)]
                        + "ms min=" + samples[0] + "ms max=" + samples[rounds - 1] + "ms");
            } catch (Throwable e) {
                record(name, false, "EXCEPTION " + e);
            }
        }
    }

    /** A concurrent reader must never observe a half-written state file. */
    private static void t8ConcurrentAtomicOverwrite() {
        String name = "T8 concurrent tmp+rename never yields a torn read";
        try {
            SmbFile state = file("t8.json");
            write(state, marker('A', 64 * 1024));

            int writers = 4;
            AtomicInteger torn = new AtomicInteger();
            AtomicInteger reads = new AtomicInteger();
            AtomicInteger writeErrors = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(writers + 1);
            volatileFlag.set(true);

            for (int i = 0; i < writers; i++) {
                final char c = (char) ('A' + i);
                new Thread(() -> {
                    try {
                        for (int r = 0; r < 5; r++) {
                            SmbFile tmp = new SmbFile(scratchUrl + "t8-" + c + ".tmp", ctx);
                            write(tmp, marker(c, 64 * 1024));
                            tmp.renameTo(new SmbFile(scratchUrl + "t8.json", ctx), true);
                        }
                    } catch (Throwable e) {
                        writeErrors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }, "t8-w" + c).start();
            }
            new Thread(() -> {
                try {
                    while (volatileFlag.get()) {
                        try {
                            String s = read(new SmbFile(scratchUrl + "t8.json", ctx));
                            reads.incrementAndGet();
                            if (s.length() != 64 * 1024 || s.chars().distinct().count() != 1) {
                                torn.incrementAndGet();
                            }
                        } catch (Throwable ignored) {
                            // A miss (file momentarily absent) is itself a finding; count it as torn.
                            torn.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            }, "t8-r").start();

            Thread.sleep(4000);
            volatileFlag.set(false);
            done.await();
            boolean ok = torn.get() == 0 && writeErrors.get() == 0;
            record(name, ok, "reads=" + reads.get() + " torn-or-missing=" + torn.get()
                    + " write-errors=" + writeErrors.get());
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean volatileFlag =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** A client that crashed holding the lock must not wedge the share forever. */
    private static void t9StaleLockTakeover() {
        String name = "T9 stale lock takeover (delete + createNewFile race)";
        try {
            SmbFile lock = file("t9.lock");
            if (lock.exists()) lock.delete();
            lock.createNewFile();   // simulate the crashed owner

            int n = 4;
            AtomicInteger winners = new AtomicInteger();
            AtomicInteger losers = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                new Thread(() -> {
                    try {
                        start.await();
                        SmbFile l = new SmbFile(scratchUrl + "t9.lock", ctx);
                        try { l.delete(); } catch (Throwable ignored) {}
                        try {
                            new SmbFile(scratchUrl + "t9.lock", ctx).createNewFile();
                            winners.incrementAndGet();
                        } catch (Throwable e) {
                            losers.incrementAndGet();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                }, "t9-" + i).start();
            }
            start.countDown();
            done.await();
            boolean ok = winners.get() == 1;
            record(name, ok, "winners=" + winners.get() + " losers=" + losers.get()
                    + (winners.get() > 1 ? "  <-- naive delete-then-create is NOT safe" : ""));
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    /** The cost of the authoritative half of the three-way reconcile. */
    private static void t10ShareRootListing(String shareUrl) {
        String name = "T10 share-root listFiles() latency";
        try {
            long t0 = System.nanoTime();
            SmbFile[] children = new SmbFile(shareUrl, ctx).listFiles();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int dirs = 0;
            long t1 = System.nanoTime();
            for (SmbFile c : children) {
                if (c.isDirectory()) {
                    dirs++;
                    c.createTime();
                }
            }
            long walkMs = (System.nanoTime() - t1) / 1_000_000;
            record(name, true, children.length + " entries (" + dirs + " dirs) listFiles=" + ms
                    + "ms, isDirectory+createTime walk=" + walkMs + "ms");
        } catch (Throwable e) {
            record(name, false, "EXCEPTION " + e);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static CIFSContext buildContext(String user, String pass, boolean signingDisabled) throws Exception {
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
        return base.withCredentials(new NtlmPasswordAuthenticator(null, user, pass));
    }

    /** Mirrors SmbPaths.buildShareUrl so the probe addresses the share exactly like the app does. */
    private static String buildShareUrl(String host, String port, String share, String path) throws Exception {
        StringBuilder url = new StringBuilder("smb://").append(host);
        if (port != null && !port.isEmpty() && !port.equals("445")) {
            url.append(":").append(port);
        }
        url.append("/").append(URLEncoder.encode(share, "UTF-8").replace("+", "%20"));
        url.append(path);
        return url.toString();
    }

    private static SmbFile file(String n) throws Exception { return new SmbFile(scratchUrl + n, ctx); }

    private static SmbFile dir(String n) throws Exception { return new SmbFile(scratchUrl + n + "/", ctx); }

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
            return bos.toString(StandardCharsets.UTF_8);
        }
    }

    private static String payloadOf(int kb) {
        return marker('p', kb * 1024);
    }

    private static String marker(char c, int len) {
        char[] a = new char[len];
        java.util.Arrays.fill(a, c);
        return new String(a);
    }

    private static void deleteRecursive(SmbFile f) throws Exception {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            SmbFile[] kids = f.listFiles();
            if (kids != null) {
                for (SmbFile k : kids) {
                    try { deleteRecursive(k); } catch (Throwable ignored) {}
                }
            }
        }
        f.delete();
    }

    private static void record(String name, boolean ok, String detail) {
        if (!ok) failures++;
        String line = (ok ? "  PASS  " : "  FAIL  ") + name + "\n          " + detail;
        results.add(line);
        System.out.println((ok ? "[pass] " : "[FAIL] ") + name + " -- " + detail);
    }

    private static String req(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required property: " + k);
        }
        return v;
    }

    private JcifsProbe() {}
}
