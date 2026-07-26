package io.junction.net;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps concurrent client connections (R-5, FR-4.1).
 *
 * <p>Shared across every worker EventLoop, so the counter is atomic. That is not
 * a violation of R-3: an atomic increment never parks a thread — the rule bans
 * blocking, not shared state.
 *
 * <p>Over the cap the connection is closed immediately rather than answered with
 * a status. At the point we are refusing connections, spending a response on each
 * one is exactly the cost we cannot afford.
 */
@ChannelHandler.Sharable
public final class ConnectionLimitHandler extends ChannelInboundHandlerAdapter {

    private final int maxConnections;
    private final AtomicInteger active = new AtomicInteger();

    public ConnectionLimitHandler(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (active.incrementAndGet() > maxConnections) {
            active.decrementAndGet();
            ctx.close();
            return;
        }
        ctx.channel().closeFuture().addListener(f -> active.decrementAndGet());
        ctx.fireChannelActive();
    }

    public int activeConnections() {
        return active.get();
    }
}
