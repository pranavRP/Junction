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
        byte[] body = (status.code() + " " + status.reasonPhrase() + "\n").getBytes(CharsetUtil.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        if (reason != null) {
            // Reason is a closed-enum token (R-33), safe to echo; aids debugging
            // when a client reports "I got a 503" with no other context.
            resp.headers().set("X-Junction-Reason", reason);
        }
        return ch.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }
}
