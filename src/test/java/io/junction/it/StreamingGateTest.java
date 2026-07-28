package io.junction.it;

import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Phase 1 gate: a 1 GB upload streams through with heap growth under 50 MB.
 *
 * <p>The assertion on heap delta is the stated gate, but the stronger proof is
 * the test JVM's own {@code -Xmx256m} (set in build.gradle.kts). One gigabyte
 * cannot fit in a 256 MB heap, so an implementation that buffered the body would
 * die with OutOfMemoryError here rather than quietly failing a threshold. The
 * limit is the test.
 */
class StreamingGateTest {

    private static final long UPLOAD_BYTES =
            Long.getLong("junction.gate.uploadBytes", 1024L * 1024 * 1024);
    private static final long MAX_HEAP_GROWTH_BYTES = 50L * 1024 * 1024;
    private static final int CHUNK = 64 * 1024;

    @Test
    void oneGigabyteUploadStreamsWithoutBufferingIntoHeap() throws Exception {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();

        // This test measures heap, so it must not also be a timing test. The
        // request timeout has to clear the whole transfer, because Junction arms
        // that timer when the request head goes upstream and only cancels it when
        // the response head comes back — and a backend cannot answer a 1 GB POST
        // until it has received all of it. The default 30s therefore sits barely
        // above the ~21s transfer and flips on ordinary machine variance.
        //
        // That coupling is a real design flaw, not just test fragility: it means
        // "request timeout" is currently a total-transaction timeout, so a slow
        // client legitimately uploading a large body is killed by a limit meant
        // to catch a silent backend. Tracked as OPQ-009; fixing it belongs with
        // the timeout work, not here.
        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withRequestTimeout(
                ProxyHarness.withMaxBodyBytes(s, 4L * 1024 * 1024 * 1024), 10 * 60_000));
             RawHttp c = new RawHttp(h.port())) {

            long before = settledHeapUsed(memory);

            c.write("POST /upload HTTP/1.1\r\nHost: h\r\n"
                    + "Content-Length: " + UPLOAD_BYTES + "\r\n\r\n");

            byte[] chunk = new byte[CHUNK];
            java.util.Arrays.fill(chunk, (byte) 'x');
            OutputStream out = c.outputStream();

            long sent = 0;
            while (sent < UPLOAD_BYTES) {
                int n = (int) Math.min(CHUNK, UPLOAD_BYTES - sent);
                out.write(chunk, 0, n);
                sent += n;
            }
            out.flush();

            RawHttp.Response r = c.readResponse();
            long after = settledHeapUsed(memory);

            assertEquals(200, r.status());
            assertEquals(String.valueOf(UPLOAD_BYTES), r.headers().get("X-Received-Bytes"),
                    "backend must receive every uploaded byte");

            long growth = after - before;
            System.out.printf("[gate] uploaded %d MB, heap %d MB -> %d MB (delta %+d MB)%n",
                    UPLOAD_BYTES / 1024 / 1024,
                    before / 1024 / 1024, after / 1024 / 1024, growth / 1024 / 1024);

            assertTrue(growth < MAX_HEAP_GROWTH_BYTES,
                    "heap grew by " + (growth / 1024 / 1024) + " MB streaming "
                            + (UPLOAD_BYTES / 1024 / 1024) + " MB; gate allows "
                            + (MAX_HEAP_GROWTH_BYTES / 1024 / 1024) + " MB");
        }
    }

    /**
     * Reads heap after collection so the delta reflects retained memory rather
     * than uncollected garbage. No sleep (R-23) — the collection request itself
     * is the synchronisation point.
     */
    private static long settledHeapUsed(MemoryMXBean memory) {
        System.gc();
        System.gc();
        return memory.getHeapMemoryUsage().getUsed();
    }
}
