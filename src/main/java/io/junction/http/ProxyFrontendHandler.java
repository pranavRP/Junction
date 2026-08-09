package io.junction.http;

import io.junction.backend.BackendPool;
import io.junction.backend.BackendRuntime;
import io.junction.balance.PickResult;
import io.junction.config.ServerConfig;
import io.junction.pool.UpstreamPool;
import io.junction.route.RouteResult;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The request lifecycle: route, balance, acquire, stream up, stream back
 * (architecture.md §4).
 *
 * <p><b>Streaming, never aggregating.</b> No {@code HttpObjectAggregator} in
 * either pipeline; bodies move as chunks and are written to the peer as they
 * arrive, so a 1 GB upload occupies chunk-sized buffers rather than 1 GB of heap.
 *
 * <p><b>Threading.</b> Upstream connections are acquired on the downstream
 * channel's own EventLoop (R-4), so every field here is touched by one thread and
 * needs no synchronisation (R-3). Nothing here blocks.
 *
 * <p><b>Connections outlive requests.</b> Phase 1 pinned one upstream channel per
 * downstream channel; that connection is now taken from {@link UpstreamPool} per
 * request and returned when the response completes, so an idle client no longer
 * holds a backend socket hostage and upstream concurrency is no longer capped by
 * the downstream connection count.
 */
public final class ProxyFrontendHandler extends ChannelInboundHandlerAdapter {

    private final ProxyContext context;
    private final ServerConfig server;

    /**
     * Inbound messages not yet handed upstream. Non-empty only while acquiring a
     * connection or while a pipelined request waits its turn. Bounded (R-5).
     */
    private final Deque<Object> inbox = new ArrayDeque<>();
    private static final int MAX_PENDING_MESSAGES = 64;

    private Channel upstream;
    private BackendRuntime backend;
    private UpstreamPool connectionPool;
    private boolean acquiring;

    private boolean awaitingResponse;
    private boolean shortCircuited;
    private boolean pendingFlushUpstream;
    /** Whether the backend's response permits reusing its connection. */
    private boolean upstreamReusable = true;

    private long requestBodyBytes;
    private boolean downstreamKeepAlive = true;
    private String requestId = "";
    private ScheduledFuture<?> requestTimeout;

    public ProxyFrontendHandler(ProxyContext context) {
        this.context = context;
        this.server = context.server();
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

    private void pump(ChannelHandlerContext ctx) {
        while (!inbox.isEmpty()) {
            Object head = inbox.peekFirst();

            if (head instanceof HttpRequest req) {
                // One request in flight per connection (design.md §12.5): a second
                // head sent upstream mid-response would interleave two messages on
                // one connection with no way to demultiplex them.
                if (awaitingResponse) {
                    break;
                }
                if (HttpUtil.getContentLength(req, -1L) > server.maxBodyBytes()) {
                    inbox.pollFirst();
                    fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "body_too_large");
                    break;
                }
                if (!ensureUpstream(ctx, req)) {
                    break; // acquiring, or already failed
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

    /** Routes, balances, and acquires. Returns true only when ready to send. */
    private boolean ensureUpstream(ChannelHandlerContext ctx, HttpRequest req) {
        if (acquiring) {
            return false;
        }
        if (upstream != null && upstream.isActive()) {
            return true;
        }

        RouteResult route = context.router().resolve(req.headers().get("Host"), req.uri());
        if (!(route instanceof RouteResult.Matched matched)) {
            drainInboxUpTo(LastHttpContent.class);
            fail(ctx, HttpResponseStatus.NOT_FOUND, "no_route");
            return false;
        }
        BackendPool pool = context.pools().byName(matched.pool()).orElse(null);
        if (pool == null) {
            fail(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "unknown_pool");
            return false;
        }

        PickResult pick = pool.pick(HashKeys.extract(req, pool.config().hashKey()));
        if (!(pick instanceof PickResult.Chosen chosen)) {
            // R-7: no live backend is a defined outcome with its own status and
            // reason, not an exception. Panic mode (FR-3.6) lands in Phase 3.
            String reason = ((PickResult.NoneAvailable) pick).reason();
            drainInboxUpTo(LastHttpContent.class);
            fail(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, reason);
            return false;
        }

        backend = chosen.backend();
        backend.requestStarted();
        connectionPool = context.connectionPools().get(pool.name());
        acquiring = true;

        // Stop reading for the acquire window. The inbox is a safety net for what
        // the codec already decoded, not a buffer to fill: a client uploading at
        // memory speed would overrun its bound in milliseconds (SUR-001).
        ctx.channel().config().setAutoRead(false);

        Future<Channel> future = connectionPool.acquire(
                ctx.channel().eventLoop(), backend.id(), backend.host(), backend.port());
        future.addListener(f -> {
            acquiring = false;
            if (f.isSuccess()) {
                upstream = (Channel) f.getNow();
                ProxyBackendHandler handler = upstream.pipeline().get(ProxyBackendHandler.class);
                if (handler == null) {
                    upstream.close();
                    upstream = null;
                    releaseInflight();
                    fail(ctx, HttpResponseStatus.BAD_GATEWAY, "upstream_pipeline_missing");
                    return;
                }
                handler.attach(this, ctx.channel());
                pump(ctx);
                resumeReads(ctx);
            } else {
                releaseInflight();
                drainInbox();
                fail(ctx, HttpResponseStatus.BAD_GATEWAY, "connect_failure");
            }
        });
        return false;
    }

    private void startRequest(ChannelHandlerContext ctx, HttpRequest req) {
        requestBodyBytes = 0;
        shortCircuited = false;
        upstreamReusable = true;
        downstreamKeepAlive = HttpUtil.isKeepAlive(req);

        String incomingId = req.headers().get(HeaderRewriter.X_REQUEST_ID);
        requestId = (incomingId == null || incomingId.isBlank())
                ? UUID.randomUUID().toString()
                : incomingId;

        HeaderRewriter.forRequest(req, clientIp(ctx.channel()), "http", requestId);

        awaitingResponse = true;
        scheduleRequestTimeout(ctx);
        writeUpstream(ctx, req);
    }

    private void forwardContent(ChannelHandlerContext ctx, HttpContent content) {
        requestBodyBytes += content.content().readableBytes();
        if (requestBodyBytes > server.maxBodyBytes()) {
            ReferenceCountUtil.release(content);
            cancelRequestTimeout();
            // The backend has a partial body it will never see the end of, so this
            // connection cannot be pooled.
            upstreamReusable = false;
            finishRequest();
            fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "body_too_large");
            return;
        }
        writeUpstream(ctx, content);
    }

    /**
     * Writes without flushing; {@link #pump} flushes once per batch. Flushing per
     * chunk would cost a syscall per chunk and dominate a large upload.
     */
    private void writeUpstream(ChannelHandlerContext ctx, Object msg) {
        if (upstream == null || !upstream.isActive()) {
            ReferenceCountUtil.release(msg);
            upstreamReusable = false;
            finishRequest();
            fail(ctx, HttpResponseStatus.BAD_GATEWAY, "upstream_gone");
            return;
        }
        upstream.write(msg);
        pendingFlushUpstream = true;
        if (!upstream.isWritable()) {
            ctx.channel().config().setAutoRead(false);
        }
    }

    private void flushUpstream() {
        if (pendingFlushUpstream && upstream != null && upstream.isActive()) {
            pendingFlushUpstream = false;
            upstream.flush();
        }
    }

    private void resumeReads(ChannelHandlerContext ctx) {
        if (!acquiring && upstream != null && upstream.isActive() && upstream.isWritable()) {
            ctx.channel().config().setAutoRead(true);
        }
    }

    // --------------------------------------------------------------- upstream

    void onUpstreamMessage(ChannelHandlerContext upstreamCtx, Object msg, Channel downstream) {
        if (msg instanceof HttpResponse resp) {
            cancelRequestTimeout();
            // Read the backend's intent before the rewrite strips Connection.
            upstreamReusable = HttpUtil.isKeepAlive(resp);
            HeaderRewriter.forResponse(resp, downstreamKeepAlive, requestId);
        }

        if (!downstream.isActive()) {
            ReferenceCountUtil.release(msg);
            return;
        }

        boolean last = msg instanceof LastHttpContent;
        ChannelFuture write = downstream.writeAndFlush(msg);

        // Reverse valve: a slow client throttles the backend instead of buffering
        // the response into our heap.
        if (!downstream.isWritable()) {
            upstreamCtx.channel().config().setAutoRead(false);
        }

        if (last) {
            completeResponse(write, downstream);
        }
    }

    private void completeResponse(ChannelFuture lastWrite, Channel downstream) {
        awaitingResponse = false;
        finishRequest();

        if (!downstreamKeepAlive) {
            lastWrite.addListener(ChannelFutureListener.CLOSE);
            return;
        }
        downstream.config().setAutoRead(true);
        ChannelHandlerContext ctx = downstream.pipeline().context(this);
        if (ctx != null) {
            pump(ctx);
        }
    }

    void onUpstreamWritabilityChanged(Channel downstream, boolean writable) {
        if (writable && downstream != null) {
            downstream.config().setAutoRead(true);
            ChannelHandlerContext ctx = downstream.pipeline().context(this);
            if (ctx != null) {
                pump(ctx);
            }
        }
    }

    void onUpstreamInactive(Channel downstream) {
        upstream = null;   // already closing; never return it to the pool
        if (awaitingResponse) {
            awaitingResponse = false;
            cancelRequestTimeout();
            releaseInflight();
            drainInbox();
            // Response bytes may already be downstream; a partial body cannot be
            // retracted, so closing is the only honest recovery.
            Responses.sendAndClose(downstream, HttpResponseStatus.BAD_GATEWAY, "upstream_closed");
        } else {
            releaseInflight();
        }
    }

    void onUpstreamError(Channel downstream, Throwable cause) {
        upstreamReusable = false;
        if (upstream != null) {
            upstream.close();
            upstream = null;
        }
        if (awaitingResponse) {
            awaitingResponse = false;
            cancelRequestTimeout();
            releaseInflight();
            drainInbox();
            Responses.sendAndClose(downstream, HttpResponseStatus.BAD_GATEWAY, "upstream_error");
        } else {
            releaseInflight();
        }
    }

    // ------------------------------------------------------- request teardown

    /**
     * Ends one request: returns the connection to the pool (or closes it) and
     * drops the in-flight count. Idempotent, because several paths can reach it
     * and a double decrement would make this backend permanently look idle.
     */
    private void finishRequest() {
        Channel channel = upstream;
        upstream = null;
        if (channel != null) {
            ProxyBackendHandler handler = channel.pipeline().get(ProxyBackendHandler.class);
            if (handler != null) {
                handler.detach();
            }
            if (upstreamReusable && channel.isActive() && connectionPool != null) {
                channel.config().setAutoRead(true);  // ready for its next owner
                connectionPool.release(channel.eventLoop(), backendIdOf(channel), channel);
            } else {
                channel.close();
            }
        }
        releaseInflight();
    }

    private String backendIdOf(Channel channel) {
        return backend == null ? "" : backend.id();
    }

    /** R-6: the in-flight permit is released on every path, exactly once. */
    private void releaseInflight() {
        if (backend != null) {
            backend.requestFinished();
            backend = null;
        }
    }

    // -------------------------------------------------------------- timeouts

    private void scheduleRequestTimeout(ChannelHandlerContext ctx) {
        cancelRequestTimeout();
        requestTimeout = ctx.executor().schedule(() -> {
            upstreamReusable = false;   // a backend mid-answer cannot be reused
            awaitingResponse = false;
            finishRequest();
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
            // Silence we caused by suppressing reads is not the client being idle;
            // timing it out would punish a peer that is blocked on us (SUR-002).
            if (!ctx.channel().config().isAutoRead()) {
                return;
            }
            if (awaitingResponse || !inbox.isEmpty()) {
                upstreamReusable = false;
                finishRequest();
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
        if (ctx.channel().isWritable() && upstream != null && upstream.isActive()) {
            upstream.config().setAutoRead(true);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelRequestTimeout();
        drainInbox();
        // The client vanished mid-request, so the backend is mid-message and its
        // connection is unusable by anyone else.
        if (awaitingResponse) {
            upstreamReusable = false;
        }
        finishRequest();
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cancelRequestTimeout();
        drainInbox();
        upstreamReusable = false;
        finishRequest();
        ctx.close();
    }

    private static String clientIp(Channel ch) {
        if (ch.remoteAddress() instanceof InetSocketAddress addr && addr.getAddress() != null) {
            return addr.getAddress().getHostAddress();
        }
        return "";
    }
}
