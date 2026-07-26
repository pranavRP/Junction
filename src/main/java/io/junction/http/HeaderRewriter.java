package io.junction.http;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Header rewriting on the forward and reverse legs (FR-1.3).
 *
 * <p>Hop-by-hop headers describe <em>this</em> TCP connection, not the message.
 * Forwarding them corrupts the next hop's framing and connection management, so
 * they are removed on both legs and the framing is then re-established from what
 * we actually intend to send.
 */
public final class HeaderRewriter {

    public static final String X_FORWARDED_FOR = "X-Forwarded-For";
    public static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";
    public static final String X_REQUEST_ID = "X-Request-ID";

    /** RFC 9110 §7.6.1 plus the two Proxy-* headers that must never be relayed. */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "transfer-encoding",
            "te",
            "trailer",
            "upgrade",
            "proxy-authenticate",
            "proxy-authorization",
            "proxy-connection");

    private HeaderRewriter() {}

    /**
     * Rewrites a client request for transmission upstream.
     *
     * @param clientIp peer address of the downstream connection, appended to XFF
     * @param scheme   {@code http} or {@code https} as seen by the client
     * @param requestId id to stamp when the client did not supply one
     */
    public static void forRequest(HttpRequest req, String clientIp, String scheme, String requestId) {
        HttpHeaders h = req.headers();

        // Capture framing intent BEFORE stripping, then restate it after. We are
        // streaming, so if the client used chunked we stay chunked upstream — we
        // cannot synthesise a Content-Length we do not know.
        boolean chunked = HttpUtil.isTransferEncodingChunked(req);
        long contentLength = HttpUtil.getContentLength(req, -1L);

        stripHopByHop(h);

        if (chunked) {
            HttpUtil.setTransferEncodingChunked(req, true);
        } else if (contentLength >= 0) {
            h.set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        }

        appendForwardedFor(h, clientIp);
        h.set(X_FORWARDED_PROTO, scheme);
        if (!h.contains(X_REQUEST_ID)) {
            h.set(X_REQUEST_ID, requestId);
        }

        // We own the upstream connection's lifetime, independently of what the
        // client asked for on its own connection. Phase 1 reuses one upstream
        // channel per downstream channel; Phase 2 replaces this with a pool.
        h.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    }

    /**
     * Rewrites a backend response for transmission downstream.
     *
     * @param keepAlive whether the <em>downstream</em> connection should survive
     */
    public static void forResponse(HttpResponse resp, boolean keepAlive, String requestId) {
        HttpHeaders h = resp.headers();

        boolean chunked = HttpUtil.isTransferEncodingChunked(resp);
        long contentLength = HttpUtil.getContentLength(resp, -1L);

        stripHopByHop(h);

        if (chunked) {
            HttpUtil.setTransferEncodingChunked(resp, true);
        } else if (contentLength >= 0) {
            h.set(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        }

        // Echo the correlation id so a client can tie its request to our logs.
        if (!h.contains(X_REQUEST_ID)) {
            h.set(X_REQUEST_ID, requestId);
        }

        h.set(HttpHeaderNames.CONNECTION,
                keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
    }

    /**
     * Removes the fixed hop-by-hop set, plus every header the sender explicitly
     * nominated in its own {@code Connection} header. That second part is easy to
     * forget and is the reason a stray {@code Connection: X-Custom} can otherwise
     * leak a connection-scoped header to the next hop.
     */
    public static void stripHopByHop(HttpHeaders h) {
        List<String> nominated = new ArrayList<>(2);
        for (String value : h.getAll(HttpHeaderNames.CONNECTION)) {
            for (String token : value.split(",")) {
                String t = token.trim();
                if (!t.isEmpty() && !t.equalsIgnoreCase("close") && !t.equalsIgnoreCase("keep-alive")) {
                    nominated.add(t);
                }
            }
        }
        for (String name : HOP_BY_HOP) {
            h.remove(name);
        }
        for (String name : nominated) {
            h.remove(name);
        }
    }

    /**
     * Appends to any existing {@code X-Forwarded-For} rather than overwriting.
     * Overwriting destroys the chain when Junction sits behind another proxy.
     */
    private static void appendForwardedFor(HttpHeaders h, String clientIp) {
        if (clientIp == null || clientIp.isEmpty()) {
            return;
        }
        String existing = h.get(X_FORWARDED_FOR);
        h.set(X_FORWARDED_FOR, existing == null || existing.isBlank()
                ? clientIp
                : existing + ", " + clientIp);
    }

    /** True if this message's framing means a body may follow. */
    public static boolean mayHaveBody(HttpMessage msg) {
        return HttpUtil.isTransferEncodingChunked(msg) || HttpUtil.getContentLength(msg, -1L) > 0;
    }

    static boolean isHopByHop(String name) {
        return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }
}
