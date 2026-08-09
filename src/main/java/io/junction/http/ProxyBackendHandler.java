package io.junction.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * Upstream tail of the pipeline. Holds no request state and makes no decisions:
 * every event is forwarded to the {@link ProxyFrontendHandler} that currently
 * owns this connection.
 *
 * <p><b>Attach/detach exists because connections outlive requests.</b> A pooled
 * channel is reused by whichever downstream connection acquires it next, so the
 * owner is set on acquire and cleared on release. Both happen on the channel's
 * own EventLoop (R-4), so the fields need no synchronisation despite changing
 * over the channel's life.
 */
final class ProxyBackendHandler extends ChannelInboundHandlerAdapter {

    private ProxyFrontendHandler front;
    private Channel downstream;

    void attach(ProxyFrontendHandler front, Channel downstream) {
        this.front = front;
        this.downstream = downstream;
    }

    void detach() {
        this.front = null;
        this.downstream = null;
    }

    boolean isAttached() {
        return front != null;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (front == null) {
            // Data on an idle pooled connection: the backend is out of sync with
            // us, so this connection can never be trusted to frame correctly
            // again. Drop it rather than hand it to the next request.
            ReferenceCountUtil.release(msg);
            ctx.close();
            return;
        }
        front.onUpstreamMessage(ctx, msg, downstream);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        if (front != null) {
            front.onUpstreamWritabilityChanged(downstream, ctx.channel().isWritable());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (front != null) {
            front.onUpstreamInactive(downstream);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (front != null) {
            front.onUpstreamError(downstream, cause);
        }
        ctx.close();
    }
}
