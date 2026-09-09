package io.junction.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 4: the two halves of the transaction timeout (OPQ-009).
 *
 * <p>Between them these two tests pin the split. One proves the response timeout
 * no longer spans the upload; the other proves that moving it did not leave the
 * upload unguarded. Either one alone would pass against a broken design — the
 * first against a proxy with no upload timeout at all, the second against the
 * total-transaction timer this replaced.
 */
class TimeoutIntegrationTest {

    /**
     * <b>The bug OPQ-009 recorded.</b> {@code request_timeout_ms} used to be armed
     * when the request head went upstream, so it covered the whole upload: a
     * client pushing a large body over a slow link was killed by a limit that
     * exists to catch a <em>silent backend</em>. Here the upload takes about
     * three times the timeout and the backend answers instantly, which is the
     * shape of a healthy slow client and must succeed.
     */
    @Test
    void aSlowUploadIsNotKilledByTheResponseTimeout() throws Exception {
        int chunks = 8;
        int chunkBytes = 64;
        long uploadMs = chunks * 150L;

        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withRequestTimeout(s, 400));
             RawHttp c = new RawHttp(h.port())) {

            c.write("POST /upload HTTP/1.1\r\nHost: h\r\n"
                    + "Content-Length: " + (chunks * chunkBytes) + "\r\n\r\n");

            byte[] chunk = new byte[chunkBytes];
            Arrays.fill(chunk, (byte) 'x');
            for (int i = 0; i < chunks; i++) {
                c.writeBytes(chunk);
                c.flush();
                Thread.sleep(150);
            }

            RawHttp.Response r = c.readResponse();
            assertEquals(200, r.status(),
                    "an upload of ~" + uploadMs + "ms must survive a 400ms backend timeout");
            assertEquals(String.valueOf(chunks * chunkBytes), r.headers().get("X-Received-Bytes"));
        }
    }

    /**
     * <b>The hole moving that timer would otherwise open.</b> The backend stops
     * reading, so the proxy's upstream write buffer fills, its own backpressure
     * switches downstream reads off, and the idle timer is suppressed on purpose
     * because the client's silence is our doing (SUR-002). With no timer left
     * covering the upload, this connection would hang until one side gave up.
     */
    @Test
    void anUploadThatStopsMakingProgressIsTimedOut() throws Exception {
        long declared = 32L * 1024 * 1024;

        try (ProxyHarness h = ProxyHarness.start(s -> ProxyHarness.withStallTimeout(s, 500));
             RawHttp c = new RawHttp(h.port())) {

            c.write("POST /upload HTTP/1.1\r\nHost: h\r\nX-Chaos-Stall: 1\r\n"
                    + "Content-Length: " + declared + "\r\n\r\n");

            // Pushes until the socket refuses to take more, which happens long
            // before the declared length — every buffer between here and the
            // backend is full and nothing is draining them.
            Thread writer = new Thread(() -> {
                byte[] chunk = new byte[64 * 1024];
                Arrays.fill(chunk, (byte) 'x');
                OutputStream out = c.outputStream();
                try {
                    for (long sent = 0; sent < declared; sent += chunk.length) {
                        out.write(chunk);
                    }
                    out.flush();
                } catch (IOException expected) {
                    // Junction gave up on this request and closed. That is the
                    // assertion below, not a failure here.
                }
            }, "stalled-uploader");
            writer.setDaemon(true);
            writer.start();

            RawHttp.Response r = c.readResponse();
            assertEquals(504, r.status());
            assertEquals("upstream_stalled", r.headers().get("X-Junction-Reason"));
        }
    }
}
