package io.junction.spike;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

/**
 * PHASE 0 SPIKE — throwaway. Deleted in Phase 1 (see phases.md).
 *
 * The single job of this file: prove client -> junction -> backend -> client works
 * on Netty, and produce one throughput number. Everything the real proxy needs
 * (streaming, routing, pooling, health, retries) is deliberately absent.
 *
 * Simplifications that are FINE for a spike but WRONG for Phase 1:
 *   - HttpObjectAggregator buffers the whole body (Phase 1 must stream, FR-1.4).
 *   - One fresh backend TCP connection per request, closed after (no pool).
 *   - Backend host/port hardcoded from env, no routing table.
 */
public final class JunctionProxy {

    public static void main(String[] args) throws Exception {
        int port = envInt("PORT", 8080);
        String backendHost = System.getenv().getOrDefault("BACKEND_HOST", "127.0.0.1");
        int backendPort = envInt("BACKEND_PORT", 8000);

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                            ch.pipeline().addLast(new ProxyFrontHandler(backendHost, backendPort));
                        }
                    });
            Channel ch = b.bind(port).sync().channel();
            System.out.println("[junction-spike] listening on :" + port
                    + " -> " + backendHost + ":" + backendPort);
            ch.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            workers.shutdownGracefully();
        }
    }

    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        return v == null ? def : Integer.parseInt(v.trim());
    }

    /** Client-facing side: accepts a request, opens an upstream connection, forwards. */
    static final class ProxyFrontHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final String backendHost;
        private final int backendPort;

        ProxyFrontHandler(String backendHost, int backendPort) {
            this.backendHost = backendHost;
            this.backendPort = backendPort;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            final Channel inbound = ctx.channel();
            final boolean keepAlive = HttpUtil.isKeepAlive(req);

            // retain the request past this method: SimpleChannelInboundHandler
            // releases the original when channelRead0 returns.
            final FullHttpRequest forward = req.retainedDuplicate();

            Bootstrap cb = new Bootstrap()
                    // Same event loop as the inbound channel: the whole point of R-4.
                    // In the spike it costs nothing; in Phase 1 it becomes the rule.
                    .group(inbound.eventLoop())
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpClientCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(1024 * 1024));
                            ch.pipeline().addLast(new ProxyBackHandler(inbound, keepAlive));
                        }
                    });

            cb.connect(backendHost, backendPort).addListener((ChannelFuture f) -> {
                if (f.isSuccess()) {
                    f.channel().writeAndFlush(forward);
                } else {
                    forward.release();
                    sendError(inbound, HttpResponseStatus.BAD_GATEWAY, keepAlive);
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    /** Upstream side: receives the backend response, writes it back downstream. */
    static final class ProxyBackHandler extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final Channel inbound;
        private final boolean keepAlive;

        ProxyBackHandler(Channel inbound, boolean keepAlive) {
            this.inbound = inbound;
            this.keepAlive = keepAlive;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse resp) {
            FullHttpResponse copy = resp.retainedDuplicate();
            HttpUtil.setKeepAlive(copy, keepAlive);
            ChannelFuture w = inbound.writeAndFlush(copy);
            if (!keepAlive) {
                w.addListener(ChannelFutureListener.CLOSE);
            }
            // spike: no pooling — one upstream connection per request, closed here.
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            sendError(inbound, HttpResponseStatus.BAD_GATEWAY, keepAlive);
            ctx.close();
        }
    }

    static void sendError(Channel inbound, HttpResponseStatus status, boolean keepAlive) {
        if (!inbound.isActive()) {
            return;
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        ChannelFuture f = inbound.writeAndFlush(resp);
        if (!keepAlive) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
