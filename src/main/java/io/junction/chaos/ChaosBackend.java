package io.junction.chaos;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Controllable backend for tests and load generation (architecture.md §6).
 *
 * <p>Not part of the proxy — it lives outside the R-11 package layout on purpose,
 * because that layout describes Junction's components and this is a test fixture.
 *
 * <p>Streams rather than aggregates, so it can absorb a 1 GB upload without
 * holding it in heap. A backend that buffers would make the Phase 1 gate measure
 * the fixture instead of the proxy.
 *
 * <table><caption>Per-request knobs</caption>
 * <tr><td>{@code X-Chaos-Delay}</td><td>ms to wait before the response head</td></tr>
 * <tr><td>{@code X-Chaos-Status}</td><td>status code to return</td></tr>
 * <tr><td>{@code X-Chaos-Size}</td><td>response body size in bytes</td></tr>
 * <tr><td>{@code X-Chaos-Chunks}</td><td>emit the body as N chunked pieces</td></tr>
 * <tr><td>{@code X-Chaos-Drop}</td><td>close the socket mid-response</td></tr>
 * </table>
 */
public final class ChaosBackend {

    private final int port;
    private final String id;
    /**
     * Whole-instance health. Flipping this to false makes every response a 503,
     * which is what "this backend is broken" looks like to both a health probe
     * and a real request — the scenario the Phase 2 ejection gate exercises.
     */
    private final java.util.concurrent.atomic.AtomicBoolean healthy =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel channel;

    public ChaosBackend(int port, String id) {
        this.port = port;
        this.id = id;
    }

    /** In-process control for tests; containers use {@code /_chaos/unhealthy}. */
    public void setHealthy(boolean value) {
        healthy.set(value);
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    public static void main(String[] args) throws Exception {
        int port = envInt("BACKEND_PORT", 8000);
        String id = System.getenv().getOrDefault("BACKEND_ID", "backend");
        ChaosBackend backend = new ChaosBackend(port, id);
        backend.start();
        System.out.println("[chaos-backend " + id + "] listening on :" + backend.boundPort());
        backend.channel.closeFuture().sync();
    }

    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new Handler(id, healthy));
                    }
                });
        channel = b.bind(new InetSocketAddress(port)).sync().channel();
    }

    public int boundPort() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public void stop() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (workers != null) {
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }

    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        return v == null ? def : Integer.parseInt(v.trim());
    }

    /** Streaming request handler: counts body bytes, never retains them. */
    static final class Handler extends ChannelInboundHandlerAdapter {

        private final String id;
        private final java.util.concurrent.atomic.AtomicBoolean healthy;
        private String uri = "/";
        private long delayMs;
        private int status = 200;
        private int size = -1;
        private int chunks;
        private boolean drop;
        private boolean keepAlive = true;
        private long received;
        /**
         * Requests served on this TCP connection. Reported back as
         * {@code X-Conn-Requests}: a value > 1 is direct proof that the caller
         * reused the connection rather than reconnecting, which is how the
         * upstream keep-alive test asserts its claim instead of assuming it.
         */
        private int requestsOnConnection;

        Handler(String id, java.util.concurrent.atomic.AtomicBoolean healthy) {
            this.id = id;
            this.healthy = healthy;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpRequest req) {
                uri = req.uri();
                delayMs = header(req, "X-Chaos-Delay", 0);
                status = (int) header(req, "X-Chaos-Status", 200);
                size = (int) header(req, "X-Chaos-Size", -1);
                chunks = (int) header(req, "X-Chaos-Chunks", 0);
                drop = header(req, "X-Chaos-Drop", 0) > 0;
                keepAlive = HttpUtil.isKeepAlive(req);
                received = 0;
                requestsOnConnection++;
            }
            if (msg instanceof HttpContent content) {
                received += content.content().readableBytes();
                boolean last = msg instanceof LastHttpContent;
                ReferenceCountUtil.release(msg);   // consumed, never buffered
                if (last) {
                    if (delayMs > 0) {
                        ctx.executor().schedule(() -> respond(ctx), delayMs, TimeUnit.MILLISECONDS);
                    } else {
                        respond(ctx);
                    }
                }
                return;
            }
            ReferenceCountUtil.release(msg);
        }

        private void respond(ChannelHandlerContext ctx) {
            if (!ctx.channel().isActive()) {
                return;
            }

            // Control endpoints flip whole-instance behaviour without a redeploy,
            // so a container can be broken and repaired mid-load-test.
            if (uri.startsWith("/_chaos/healthy")) {
                healthy.set(true);
                sendPlain(ctx, HttpResponseStatus.OK, "healthy\n");
                return;
            }
            if (uri.startsWith("/_chaos/unhealthy")) {
                healthy.set(false);
                sendPlain(ctx, HttpResponseStatus.OK, "unhealthy\n");
                return;
            }

            // A broken instance fails everything, probes and real traffic alike.
            HttpResponseStatus st = healthy.get()
                    ? HttpResponseStatus.valueOf(status)
                    : HttpResponseStatus.SERVICE_UNAVAILABLE;

            if (drop) {
                // Half-written response: head only, then a hard close.
                HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, st);
                head.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
                ctx.writeAndFlush(head).addListener(ChannelFutureListener.CLOSE);
                return;
            }

            if (chunks > 0) {
                respondChunked(ctx, st);
                return;
            }

            byte[] body = size >= 0
                    ? filler(size)
                    : ("ok from " + id + " received=" + received + "\n").getBytes(CharsetUtil.UTF_8);
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, st, Unpooled.wrappedBuffer(body));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
            resp.headers().set("X-Backend-Id", id);
            resp.headers().set("X-Received-Bytes", Long.toString(received));
            resp.headers().setInt("X-Conn-Requests", requestsOnConnection);
            finish(ctx.writeAndFlush(resp), ctx);
        }

        private void respondChunked(ChannelHandlerContext ctx, HttpResponseStatus st) {
            HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, st);
            head.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            head.headers().set("X-Backend-Id", id);
            head.headers().set("X-Received-Bytes", Long.toString(received));
            head.headers().setInt("X-Conn-Requests", requestsOnConnection);
            ctx.write(head);

            int per = size > 0 ? Math.max(1, size / chunks) : 16;
            for (int i = 0; i < chunks; i++) {
                ByteBuf buf = Unpooled.wrappedBuffer(filler(per));
                ctx.write(new DefaultHttpContent(buf));
            }
            finish(ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), ctx);
        }

        private void sendPlain(ChannelHandlerContext ctx, HttpResponseStatus st, String text) {
            byte[] body = text.getBytes(CharsetUtil.UTF_8);
            FullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, st, Unpooled.wrappedBuffer(body));
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
            resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, body.length);
            resp.headers().set("X-Backend-Id", id);
            finish(ctx.writeAndFlush(resp), ctx);
        }

        private void finish(ChannelFuture f, ChannelHandlerContext ctx) {
            if (!keepAlive) {
                f.addListener(ChannelFutureListener.CLOSE);
            }
        }

        private static byte[] filler(int n) {
            byte[] b = new byte[n];
            java.util.Arrays.fill(b, (byte) 'x');
            return b;
        }

        private static long header(HttpRequest req, String name, long def) {
            String v = req.headers().get(name);
            if (v == null) {
                return def;
            }
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
