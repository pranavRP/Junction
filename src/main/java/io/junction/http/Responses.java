package io.junction.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/** Builds and sends the small error responses Junction generates itself. */
public final class Responses {

    private Responses() {}

    /**
     * Sends a status with a one-line plain-text body and closes the connection.
     *
     * <p>Junction-generated errors always close. Once we have rejected a request
     * mid-stream — an oversized body, a timeout — the client may still be sending,
     * and the remaining bytes would be parsed as a bogus next request on a reused
     * connection. Closing is the only safe framing recovery.
     */
    public static ChannelFuture sendAndClose(Channel ch, HttpResponseStatus status, String reason) {
        if (ch == null || !ch.isActive()) {
            return null;
        }
        FullHttpResponse resp = build(status, reason);
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        return ch.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Sheds one request over capacity (FR-4.2), resolving OPQ-001 in favour of
     * 503 over 429.
     *
     * <p><b>Why not 429.</b> 429 says "you are sending too much", which is a
     * statement about one client and a claim we are not entitled to make: the
     * limit here is on aggregate concurrency, and the request being shed may be
     * the only one that client has sent all day. 503 plus {@code Retry-After}
     * says "this server, right now", which is the true statement and the one a
     * well-behaved client backs off on.
     *
     * <p><b>Why this one does not always close.</b> Every other Junction-generated
     * error closes, because it happens mid-stream and the remaining request bytes
     * would be parsed as a bogus next request. A shed request with no body has no
     * remaining bytes, so the connection stays framed and reusable — and closing
     * it would charge every shed client a TCP handshake at exactly the load where
     * handshakes are the cost we cannot afford. A shed request that <em>does</em>
     * carry a body still closes: draining an upload we have already refused is
     * worse than dropping the connection.
     */
    public static ChannelFuture shed(Channel ch, boolean keepAlive) {
        if (ch == null || !ch.isActive()) {
            return null;
        }
        FullHttpResponse resp = build(HttpResponseStatus.SERVICE_UNAVAILABLE, "over_capacity");
        // Seconds, and deliberately not a config knob: it only has to be long
        // enough that a client's retry lands after the burst that shed it.
        resp.headers().set(HttpHeaderNames.RETRY_AFTER, "1");
        if (!keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            return ch.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        return ch.writeAndFlush(resp);
    }

    private static FullHttpResponse build(HttpResponseStatus status, String reason) {
        byte[] body = (status.code() + " " + status.reasonPhrase() + "\n").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        if (reason != null) {
            // Reason is a closed-enum token (R-33), safe to echo; aids debugging
            // when a client reports "I got a 503" with no other context.
            resp.headers().set("X-Junction-Reason", reason);
        }
        return resp;
    }
}
