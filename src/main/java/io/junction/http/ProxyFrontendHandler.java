package io.junction.http;

import io.junction.config.BackendConfig;
import io.junction.config.JunctionConfig;
import io.junction.config.PoolConfig;
import io.junction.config.ServerConfig;
import io.junction.route.RouteResult;
import io.junction.route.Router;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The request lifecycle: route, connect, stream up, stream back (architecture.md §4).
 *
 * <p><b>Streaming, never aggregating.</b> There is deliberately no
 * {@code HttpObjectAggregator} in either pipeline. Bodies move through as a
 * sequence of {@link HttpContent} chunks and are written to the peer as they
 * arrive, so a 1 GB upload occupies chunk-sized buffers rather than 1 GB of heap
 * (FR-1.4). This is what the Phase 1 gate measures.
 *
 * <p><b>Threading.</b> The upstream channel is bootstrapped onto the downstream
 * channel's own {@code EventLoop} (R-4), so every field below is touched by
 * exactly one thread and needs no synchronisation (R-3). Nothing here blocks.
 *
 * <p><b>Backpressure.</b> Writes are paired with a writability check on the peer;
 * when the peer's outbound buffer is full we stop reading our side, the kernel
 * receive window closes, and the original sender is throttled with no
 * application-level queue involved (DEC-005). Phase 4 adds the slow-client tests
 * and watermark tuning on top of this mechanism.
 */
public final class ProxyFrontendHandler extends ChannelInboundHandlerAdapter {

    private final Router router;
    private final JunctionConfig config;
    private final ServerConfig server;

    /**
     * Inbound messages not yet handed upstream. Non-empty only while an upstream
     * connect is in flight or a pipelined request is waiting its turn. Bounded
     * (R-5): overflow sheds rather than growing, because an unbounded queue here
     * would convert a slow backend into an out-of-memory kill.
     */
    private final Deque<Object> inbox = new ArrayDeque<>();
    private static final int MAX_PENDING_MESSAGES = 64;

    /** One upstream channel per downstream channel — Phase 1's upstream keep-alive. */
    private Channel upstream;
    private String upstreamBackendId;
    private boolean connecting;

    /** A request head has gone upstream and its response has not finished. */
    private boolean awaitingResponse;
    /** This request was already answered locally; ignore the rest of its body. */
    private boolean shortCircuited;
    private boolean pendingFlushUpstream;

    private long requestBodyBytes;
    private boolean downstreamKeepAlive = true;
    private String requestId = "";
    private ScheduledFuture<?> requestTimeout;

    public ProxyFrontendHandler(Router router, JunctionConfig config) {
        this.router = router;
        this.config = config;
        this.server = config.server();
    }

    // ------------------------------------------------------------- downstream

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (inbox.size() >= MAX_PENDING_MESSAGES) {
            ReferenceCountUtil.release(msg);
            fail(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "pending_queue_full");
            return;
        }
        inbox.addLast(msg);
        pump(ctx);
    }

    /**
     * Drains {@link #inbox} as far as the current state allows. Called on read, on
     * upstream connect, and on upstream writability change — every event that can
     * unblock forward progress.
     */
    private void pump(ChannelHandlerContext ctx) {
        while (!inbox.isEmpty()) {
            Object head = inbox.peekFirst();

            if (head instanceof HttpRequest req) {
                // Serialize pipelined requests: one in flight per connection
                // (design.md §12.5). Sending a second request head upstream while
                // a response is still streaming would interleave two messages on
                // one connection with no way to demultiplex them.
                if (awaitingResponse) {
                    break;
                }
                // A declared Content-Length over the cap is refusable before we
                // pick a backend or open a socket. Waiting to count the bytes
                // would make the client pay to upload a body we will discard.
                if (HttpUtil.getContentLength(req, -1L) > server.maxBodyBytes()) {
                    inbox.pollFirst();
                    fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "body_too_large");
                    break;
                }
                if (!ensureUpstream(ctx, req)) {
                    break; // connecting, or already failed
                }
                inbox.pollFirst();
                startRequest(ctx, req);
            } else {
                HttpContent content = (HttpContent) head;
                if (shortCircuited) {
                    inbox.pollFirst();
                    ReferenceCountUtil.release(content);
                    if (content instanceof LastHttpContent) {
                        shortCircuited = false;
                    }
                    continue;
                }
                if (upstream == null || !upstream.isActive()) {
                    break;
                }
                inbox.pollFirst();
                forwardContent(ctx, content);
            }
        }
        flushUpstream();
    }

    /** Resolves the route and starts a connect if we do not already have one. */
    private boolean ensureUpstream(ChannelHandlerContext ctx, HttpRequest req) {
        if (connecting) {
            return false;
        }
        if (upstream != null && upstream.isActive()) {
            return true;
        }

        RouteResult route = router.resolve(req.headers().get("Host"), req.uri());
        if (!(route instanceof RouteResult.Matched matched)) {
            drainInboxUpTo(LastHttpContent.class);
            fail(ctx, HttpResponseStatus.NOT_FOUND, "no_route");
            return false;
        }
        PoolConfig pool = config.pool(matched.pool()).orElse(null);
        if (pool == null || pool.backends().isEmpty()) {
            fail(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "empty_pool");
            return false;
        }
        // TODO(P2): balancer picks from live backends; Phase 1 has no health
        // state to pick on, so the first backend is the whole story.
        BackendConfig backend = pool.backends().get(0);

        connecting = true;
        connect(ctx, backend);
        return false;
    }

    private void connect(ChannelHandlerContext ctx, BackendConfig backend) {
        final Channel downstream = ctx.channel();

        // Stop reading for the duration of the connect. The inbox is a safety net
        // for whatever the codec already decoded in this read batch, not a buffer
        // we intend to fill: a client uploading at memory speed would otherwise
        // overrun its bound in milliseconds and get its own request shed. The
        // correct answer to "not ready yet" is to stop reading (DEC-005).
        downstream.config().setAutoRead(false);
        Bootstrap b = new Bootstrap()
                // R-4: same EventLoop as downstream. This is what makes every
                // field in this handler single-threaded and lock-free.
                .group(downstream.eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) server.connectTimeoutMs())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpClientCodec(
                                server.maxUriLength(), server.maxHeaderBytes(), 8192));
                        ch.pipeline().addLast(
                                new ProxyBackendHandler(ProxyFrontendHandler.this, downstream));
                    }
                });

        b.connect(new InetSocketAddress(backend.host(), backend.port()))
                .addListener((ChannelFuture f) -> {
                    connecting = false;
                    if (f.isSuccess()) {
                        upstream = f.channel();
                        upstreamBackendId = backend.id();
                        pump(ctx);
                        resumeReads(ctx);
                    } else {
                        // R-7: connect failure is an explicit path, not an
                        // exception handler. Retry belongs to Phase 3.
                        drainInbox();
                        fail(ctx, HttpResponseStatus.BAD_GATEWAY, "connect_failure");
                    }
                });
    }

    private void startRequest(ChannelHandlerContext ctx, HttpRequest req) {
        requestBodyBytes = 0;
        shortCircuited = false;
        downstreamKeepAlive = HttpUtil.isKeepAlive(req);

        String incomingId = req.headers().get(HeaderRewriter.X_REQUEST_ID);
        requestId = (incomingId == null || incomingId.isBlank())
                ? UUID.randomUUID().toString()
                : incomingId;

        String clientIp = clientIp(ctx.channel());
        HeaderRewriter.forRequest(req, clientIp, "http", requestId);

        awaitingResponse = true;
        scheduleRequestTimeout(ctx);
        writeUpstream(ctx, req);
    }

    private void forwardContent(ChannelHandlerContext ctx, HttpContent content) {
        int bytes = content.content().readableBytes();
        requestBodyBytes += bytes;
        if (requestBodyBytes > server.maxBodyBytes()) {
            ReferenceCountUtil.release(content);
            cancelRequestTimeout();
            fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "body_too_large");
            return;
        }
        writeUpstream(ctx, content);
    }

    /**
     * Writes without flushing; {@link #pump} flushes once at the end of the batch.
     * Flushing per message would issue a syscall per chunk and dominate the cost
     * of a large upload.
     */
    private void writeUpstream(ChannelHandlerContext ctx, Object msg) {
        if (upstream == null || !upstream.isActive()) {
            ReferenceCountUtil.release(msg);
            fail(ctx, HttpResponseStatus.BAD_GATEWAY, "upstream_gone");
            return;
        }
        upstream.write(msg);
        pendingFlushUpstream = true;
        // DEC-005: peer buffer full -> stop reading our side -> TCP window closes.
        if (!upstream.isWritable()) {
            ctx.channel().config().setAutoRead(false);
        }
    }

    /**
     * Re-enables reads only when there is somewhere for the bytes to go. Turning
     * autoRead back on while the upstream is still full would just move the
     * backlog from the kernel into our heap.
     */
    private void resumeReads(ChannelHandlerContext ctx) {
        if (!connecting && upstream != null && upstream.isActive() && upstream.isWritable()) {
            ctx.channel().config().setAutoRead(true);
        }
    }

    private void flushUpstream() {
        if (pendingFlushUpstream && upstream != null && upstream.isActive()) {
            pendingFlushUpstream = false;
            upstream.flush();
        }
    }

    // --------------------------------------------------------------- upstream

    /** Called by {@link ProxyBackendHandler} on the shared EventLoop. */
    void onUpstreamMessage(ChannelHandlerContext upstreamCtx, Object msg, Channel downstream) {
        if (msg instanceof HttpResponse resp) {
            cancelRequestTimeout();
            HeaderRewriter.forResponse(resp, downstreamKeepAlive, requestId);
        }

        if (!downstream.isActive()) {
            ReferenceCountUtil.release(msg);
            return;
        }

        boolean last = msg instanceof LastHttpContent;
        ChannelFuture w = downstream.writeAndFlush(msg);

        // Reverse direction of the same valve: slow client -> stop reading the
        // backend -> backend is throttled instead of buffering into our heap.
        if (!downstream.isWritable()) {
            upstreamCtx.channel().config().setAutoRead(false);
        }

        if (last) {
            completeResponse(w, downstream);
        }
    }

    private void completeResponse(ChannelFuture lastWrite, Channel downstream) {
        awaitingResponse = false;
        if (!downstreamKeepAlive) {
            lastWrite.addListener(ChannelFutureListener.CLOSE);
            return;
        }
        // Downstream stays open for the next request; so does upstream, which is
        // the entire point of Phase 1's upstream keep-alive. Phase 2 replaces the
        // 1:1 pinning with a real pool.
        downstream.config().setAutoRead(true);
        if (downstream.pipeline().context(this) != null) {
            pump(downstream.pipeline().context(this));
        }
    }

    void onUpstreamWritabilityChanged(Channel downstream, boolean writable) {
        if (writable) {
            downstream.config().setAutoRead(true);
            ChannelHandlerContext ctx = downstream.pipeline().context(this);
            if (ctx != null) {
                pump(ctx);
            }
        }
    }

    void onUpstreamInactive(Channel downstream) {
        upstream = null;
        if (awaitingResponse) {
            awaitingResponse = false;
            cancelRequestTimeout();
            // The backend closed before finishing. If we already sent response
            // bytes downstream the client sees a truncated body and we can only
            // close; there is no way to retract a partially written response.
            drainInbox();
            Responses.sendAndClose(downstream, HttpResponseStatus.BAD_GATEWAY, "upstream_closed");
        }
    }

    void onUpstreamError(Channel downstream, Throwable cause) {
        if (upstream != null) {
            upstream.close();
        }
        if (awaitingResponse) {
            awaitingResponse = false;
            cancelRequestTimeout();
            drainInbox();
            Responses.sendAndClose(downstream, HttpResponseStatus.BAD_GATEWAY, "upstream_error");
        }
    }

    // -------------------------------------------------------------- timeouts

    private void scheduleRequestTimeout(ChannelHandlerContext ctx) {
        cancelRequestTimeout();
        requestTimeout = ctx.executor().schedule(() -> {
            // R-7: an unanswered backend is a defined outcome with a defined code.
            if (upstream != null) {
                upstream.close();
            }
            awaitingResponse = false;
            drainInbox();
            Responses.sendAndClose(ctx.channel(), HttpResponseStatus.GATEWAY_TIMEOUT, "request_timeout");
        }, server.requestTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private void cancelRequestTimeout() {
        if (requestTimeout != null) {
            requestTimeout.cancel(false);
            requestTimeout = null;
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.ALL_IDLE) {
            // If we suppressed reads for backpressure, this connection only looks
            // idle because we stopped listening. Timing it out would punish a
            // client that is blocked on us, so the timer only counts while we are
            // genuinely waiting on the peer.
            if (!ctx.channel().config().isAutoRead()) {
                return;
            }
            // An idle connection mid-request is a client that stopped sending;
            // an idle connection between requests is just a quiet keep-alive.
            if (awaitingResponse || !inbox.isEmpty()) {
                Responses.sendAndClose(ctx.channel(), HttpResponseStatus.REQUEST_TIMEOUT, "idle_timeout");
            } else {
                ctx.close();
            }
        }
    }

    // ---------------------------------------------------------------- failure

    private void fail(ChannelHandlerContext ctx, HttpResponseStatus status, String reason) {
        cancelRequestTimeout();
        awaitingResponse = false;
        shortCircuited = true;
        Responses.sendAndClose(ctx.channel(), status, reason);
    }

    /** R-6: anything we queued and will never forward must be released. */
    private void drainInbox() {
        Object msg;
        while ((msg = inbox.pollFirst()) != null) {
            ReferenceCountUtil.release(msg);
        }
    }

    private void drainInboxUpTo(Class<?> stopAfter) {
        Object msg;
        while ((msg = inbox.pollFirst()) != null) {
            boolean stop = stopAfter.isInstance(msg);
            ReferenceCountUtil.release(msg);
            if (stop) {
                return;
            }
        }
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        // Downstream drained -> let the backend resume sending.
        if (ctx.channel().isWritable() && upstream != null && upstream.isActive()) {
            upstream.config().setAutoRead(true);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelRequestTimeout();
        drainInbox();
        if (upstream != null) {
            upstream.close();
            upstream = null;
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cancelRequestTimeout();
        drainInbox();
        ctx.close();
    }

    private static String clientIp(Channel ch) {
        if (ch.remoteAddress() instanceof InetSocketAddress addr && addr.getAddress() != null) {
            return addr.getAddress().getHostAddress();
        }
        return "";
    }

    String upstreamBackendId() {
        return upstreamBackendId;
    }
}
