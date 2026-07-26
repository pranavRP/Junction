package io.junction.net;

import io.junction.http.Responses;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.handler.codec.http.TooLongHttpLineException;
import io.netty.util.ReferenceCountUtil;

/**
 * Turns decoder limit violations into the status codes FR-1.6 specifies.
 *
 * <p>{@code HttpServerCodec} enforces the size caps but signals the breach as a
 * failed {@code DecoderResult} on the message rather than an exception, and a
 * generic 400 for all of them loses the distinction a client needs to fix its
 * request. Mapping cause to code is the whole job of this handler.
 *
 * <p>Sits before the proxy handler so an oversized request is rejected without
 * ever selecting a backend or opening an upstream connection.
 */
public final class LimitsHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpObject obj && obj.decoderResult().isFailure()) {
            Throwable cause = obj.decoderResult().cause();
            ReferenceCountUtil.release(msg);
            Responses.sendAndClose(ctx.channel(), statusFor(cause), reasonFor(cause));
            return;
        }
        ctx.fireChannelRead(msg);
    }

    private static HttpResponseStatus statusFor(Throwable cause) {
        if (cause instanceof TooLongHttpLineException) {
            return HttpResponseStatus.REQUEST_URI_TOO_LONG;              // 414
        }
        if (cause instanceof TooLongHttpHeaderException) {
            return HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE;   // 431
        }
        return HttpResponseStatus.BAD_REQUEST;                           // 400
    }

    /** Closed enum for the metrics {@code reason} label (R-33). */
    private static String reasonFor(Throwable cause) {
        if (cause instanceof TooLongHttpLineException) {
            return "uri_too_long";
        }
        if (cause instanceof TooLongHttpHeaderException) {
            return "headers_too_large";
        }
        return "malformed_request";
    }
}
