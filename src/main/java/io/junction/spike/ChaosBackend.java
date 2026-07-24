package io.junction.spike;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

import java.util.concurrent.TimeUnit;

/**
 * PHASE 0 SPIKE — throwaway. Deleted in Phase 1 (see phases.md / architecture.md §6).
 *
 * Chaos backend v0: answers 200 OK. Honours one knob so a load test can steer it:
 *   X-Chaos-Delay: <ms>   -> wait that long before responding (non-blocking timer).
 *
 * The full chaos header set (status / drop / slow-body / size) arrives later.
 */
public final class ChaosBackend {

    public static void main(String[] args) throws Exception {
        int port = envInt("BACKEND_PORT", 8000);
        String id = System.getenv().getOrDefault("BACKEND_ID", "backend");

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
                            ch.pipeline().addLast(new HttpObjectAggregator(64 * 1024));
                            ch.pipeline().addLast(new BackendHandler(id));
                        }
                    });
            Channel ch = b.bind(port).sync().channel();
            System.out.println("[chaos-backend " + id + "] listening on :" + port);
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

    static final class BackendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        private final String id;

        BackendHandler(String id) {
            this.id = id;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            long delayMs = 0;
            String h = req.headers().get("X-Chaos-Delay");
            if (h != null) {
                try {
                    delayMs = Long.parseLong(h.trim());
                } catch (NumberFormatException ignored) {
                    // spike: bad header value is simply ignored
                }
            }

            boolean keepAlive = HttpUtil.isKeepAlive(req);
            Runnable respond = () -> writeResponse(ctx, keepAlive);

            if (delayMs > 0) {
                // Non-blocking delay: never Thread.sleep on an event loop (R-3 in spirit).
                ctx.executor().schedule(respond, delayMs, TimeUnit.MILLISECONDS);
            } else {
                respond.run();
            }
        }

        private void writeResponse(ChannelHandlerContext ctx, boolean keepAlive) {
            byte[] body = ("ok from " + id + "\n").getBytes(CharsetUtil.UTF_8);
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
            resp.headers().set("X-Backend-Id", id);
            if (keepAlive) {
                resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            ChannelFuture f = ctx.writeAndFlush(resp);
            if (!keepAlive) {
                f.addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
