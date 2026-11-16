package com.hippo.beerbelly;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hippo.streampipe.InputStreamPipe;
import com.hippo.streampipe.OutputStreamPipe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The reader asks the cache on the GL thread, so tryContain must never wait on a page's lock. */
public class SimpleDiskCacheLockTest {

    private static final String PAGE = "image:4199283:7";
    private static final long WAIT_MS = 2000;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private SimpleDiskCache cache;

    @Before
    public void setUp() throws IOException {
        cache = new SimpleDiskCache(folder.newFolder("cache"), 1024 * 1024);
        assertTrue(cache.put(PAGE, new ByteArrayInputStream(new byte[64])));
    }

    @Test
    public void tryContain_doesNotWaitWhileAWorkerWritesThePage() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread writer = start(() -> {
            OutputStreamPipe pipe = cache.getOutputStreamPipe(PAGE);
            pipe.obtain();
            holding.countDown();
            awaitQuietly(done);
            pipe.release();
        });
        assertTrue(holding.await(WAIT_MS, TimeUnit.MILLISECONDS));

        boolean[] cached = {true};
        Thread reader = start(() -> cached[0] = cache.tryContain(PAGE));
        reader.join(WAIT_MS);
        boolean waited = reader.isAlive();

        done.countDown();
        writer.join();
        reader.join();
        assertFalse("tryContain waited for the writer", waited);
        assertFalse("a page being written is not readable yet", cached[0]);
        assertTrue(cache.tryContain(PAGE));
    }

    @Test
    public void tryContain_doesNotQueueBehindAWorkerWaitingToRemoveThePage() throws Exception {
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread decoder = start(() -> {
            InputStreamPipe pipe = cache.getInputStreamPipe(PAGE);
            pipe.obtain();
            reading.countDown();
            awaitQuietly(done);
            pipe.release();
        });
        assertTrue(reading.await(WAIT_MS, TimeUnit.MILLISECONDS));

        Thread worker = start(() -> cache.remove(PAGE));
        while (worker.isAlive() && worker.getState() != Thread.State.WAITING) {
            Thread.yield();
        }
        assertTrue("remove() should queue behind the open read pipe", worker.isAlive());

        boolean[] cached = {false};
        Thread reader = start(() -> cached[0] = cache.tryContain(PAGE));
        reader.join(WAIT_MS);
        boolean waited = reader.isAlive();

        done.countDown();
        decoder.join();
        worker.join();
        reader.join();
        assertFalse("tryContain queued behind the waiting writer", waited);
        assertTrue(cached[0]);
    }

    /** Daemon, so a lock this test gets wrong cannot keep the test JVM alive. */
    private static Thread start(Runnable body) {
        Thread thread = new Thread(body);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
