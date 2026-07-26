package io.junction.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Upstream tail of the pipeline. Deliberately holds no state and makes no
 * decisions: it forwards every event to {@link ProxyFrontendHandler}, which owns
 * the whole request lifecycle.
 *
 * <p>Keeping all state in one handler is what makes R-3 easy to satisfy — there
 * is exactly one object holding request state, touched by exactly one EventLoop
 * (R-4), so no field here needs to be volatile or guarded.
 */
final class ProxyBackendHandler extends ChannelInboundHandlerAdapter {

    private final ProxyFrontendHandler front;
    private final Channel downstream;

    ProxyBackendHandler(ProxyFrontendHandler front, Channel downstream) {
        this.front = front;
        this.downstream = downstream;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        front.onUpstreamMessage(ctx, msg, downstream);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        front.onUpstreamWritabilityChanged(downstream, ctx.channel().isWritable());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        front.onUpstreamInactive(downstream);
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        front.onUpstreamError(downstream, cause);
        ctx.close();
    }
}
