package io.junction.admit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionControllerTest {

    @Test
    void admitsUpToTheLimitAndShedsTheRest() {
        AdmissionController admit = new AdmissionController(3);

        assertTrue(admit.tryAcquire());
        assertTrue(admit.tryAcquire());
        assertTrue(admit.tryAcquire());
        assertFalse(admit.tryAcquire(), "the fourth concurrent request is over the limit");
        assertEquals(3, admit.inFlight());
    }

    @Test
    void aCompletedRequestGivesItsPermitBack() {
        AdmissionController admit = new AdmissionController(1);

        assertTrue(admit.tryAcquire());
        assertFalse(admit.tryAcquire());

        admit.release();

        assertEquals(0, admit.inFlight());
        assertTrue(admit.tryAcquire(), "the limit is concurrency, not a total");
    }

    @Test
    void zeroDisablesAdmissionControlEntirely() {
        AdmissionController admit = new AdmissionController(0);

        assertFalse(admit.enabled());
        for (int i = 0; i < 1_000; i++) {
            assertTrue(admit.tryAcquire());
        }
        admit.release();   // must stay a no-op, not raise a ceiling that does not exist
        assertEquals(0, admit.inFlight());
    }

    /**
     * The limit is enforced across worker EventLoops, so the interesting failure
     * is two threads reading the same headroom and both taking it. A
     * check-then-increment on a plain int passes every test above and fails this
     * one.
     */
    @Test
    void concurrentAcquiresNeverExceedTheLimit() throws Exception {
        int limit = 8;
        int threads = 32;
        AdmissionController admit = new AdmissionController(limit);
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        if (admit.tryAcquire()) {
                            admitted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "workers did not finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(limit, admitted.get(), "exactly the limit is admitted, never more");
        assertEquals(limit, admit.inFlight());
    }
}
