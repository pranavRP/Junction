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
 *
 * <p><b>One guard per phase, never two and never none (OPQ-009).</b> While the
 * request body is streaming, the stall check watches for forward progress; once
 * the last chunk is sent, it hands over to the response timeout, which watches
 * for a backend that has gone quiet. Arming the response timeout at the start of
 * the request instead — as Phase 1 did — makes it a total-transaction timeout, so
 * a client legitimately uploading over a slow link is killed by a limit meant for
 * a silent backend. Moving it alone is not enough: a backend that stalls
 * mid-upload silences the client through our own backpressure, which is exactly
 * the case the idle timer declines to act on (SUR-002), so the stall check has to
 * exist for the move to be safe.
 *
 * <p><b>Retries are confined to the connect failure (FR-3.4).</b> That is not
 * timidity, it is the only point in a streaming proxy where a retry is honest:
 * the request head is still sitting in the inbox and not one byte has gone
 * upstream, so re-picking a backend is genuinely a first attempt. Retrying once
 * the body has begun to stream would need the body buffered to replay it, which
 * is the exact design this proxy exists to avoid, and retrying once response
 * bytes have reached the client is not a retry at all. Every retry also has to
 * buy a token from the pool's {@link io.junction.backend.RetryBudget}, so a total
 * outage cannot turn one failing request into an unbounded storm.
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
    private BackendPool pool;
    private UpstreamPool connectionPool;
    private boolean acquiring;
    /** Attempts made for the request currently at the head of the inbox. */
    private int attempt;

    private boolean awaitingResponse;
    /** Whether this connection currently holds an admission permit. */
    private boolean admitted;
    private boolean shortCircuited;
    private boolean pendingFlushUpstream;
    /** Whether the backend's response permits reusing its connection. */
    private boolean upstreamReusable = true;

    private long requestBodyBytes;
    private boolean downstreamKeepAlive = true;
    private String requestId = "";
    /** Armed only once the request is fully sent; see {@link #armResponseTimeout}. */
    private ScheduledFuture<?> requestTimeout;
    /** Armed only while a request body is streaming; see {@link #armStallCheck}. */
    private ScheduledFuture<?> stallCheck;
    private long lastProgressNanos;

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
                // Cheapest possible rejection, and deliberately the first thing
                // tried: over capacity we spend no route lookup, no pick, and no
                // upstream connection on a request we are not going to serve.
                if (!admitted && !context.admit().tryAcquire()) {
                    inbox.pollFirst();
                    shed(ctx, req);
                    continue;
                }
                admitted = true;
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
        BackendPool target = context.pools().byName(matched.pool()).orElse(null);
        if (target == null) {
            fail(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "unknown_pool");
            return false;
        }
        pool = target;
        if (attempt == 0) {
            // The denominator of the retry budget. Counted once per client
            // request, so the ratio it enforces is retries per request rather
            // than retries per attempt, which would compound.
            pool.retryBudget().recordRequest();
        }
        attempt++;

        PickResult pick = pool.pick(HashKeys.extract(req, pool.config().hashKey()));
        if (!(pick instanceof PickResult.Chosen chosen)) {
            // R-7: no live backend is a defined outcome with its own status and
            // reason, not an exception. Reachable only with panic disabled — a
            // pool in panic answers with a backend rather than with this.
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
                // A refused or timed-out connect is the clearest passive failure
                // signal there is: no ambiguity about whether the backend saw the
                // request, because it never got one.
                recordOutcome(false);
                releaseInflight();
                if (retryConnect(ctx)) {
                    return;
                }
                drainInbox();
                fail(ctx, HttpResponseStatus.BAD_GATEWAY, "connect_failure");
            }
        });
        return false;
    }

    /**
     * Tries the request again on another backend, if the policy and the budget
     * both allow it. Safe only here: the head is still in the inbox and nothing
     * has been written upstream, so nothing is being replayed.
     */
    private boolean retryConnect(ChannelHandlerContext ctx) {
        // The head must still be an unsent request. Anything else means bytes
        // have already moved and a retry would be a replay, not a retry.
        if (pool == null || !(inbox.peekFirst() instanceof HttpRequest)) {
            return false;
        }
        if (attempt >= pool.config().retry().maxAttempts()) {
            return false;
        }
        if (!pool.retryBudget().tryRetry()) {
            return false;
        }
        pump(ctx);
        resumeReads(ctx);
        return true;
    }

    private void startRequest(ChannelHandlerContext ctx, HttpRequest req) {
        attempt = 0;   // this request is committed upstream; the next starts fresh
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
        armStallCheck(ctx);
        writeUpstream(ctx, req);
    }

    private void forwardContent(ChannelHandlerContext ctx, HttpContent content) {
        requestBodyBytes += content.content().readableBytes();
        lastProgressNanos = System.nanoTime();
        if (requestBodyBytes > server.maxBodyBytes()) {
            ReferenceCountUtil.release(content);
            cancelTimers();
            // The backend has a partial body it will never see the end of, so this
            // connection cannot be pooled.
            upstreamReusable = false;
            finishRequest();
            fail(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, "body_too_large");
            return;
        }
        writeUpstream(ctx, content);
        if (content instanceof LastHttpContent) {
            // The upload is over, so the only thing left to wait on is the
            // backend. Hand the request from the stall detector to the response
            // timeout: one guard per phase, never two and never none.
            cancelStallCheck();
            armResponseTimeout(ctx);
        }
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
            // 5xx is the backend reporting its own failure, so it feeds the
            // breaker. 4xx does not: a client sending a bad request is no
            // evidence that this backend is unwell, and counting it would let a
            // scan for /wp-admin eject a perfectly healthy pool.
            recordOutcome(resp.status().code() < 500);
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
            cancelTimers();
            recordOutcome(false);
            releaseInflight();
            releaseAdmission();
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
            cancelTimers();
            recordOutcome(false);
            releaseInflight();
            releaseAdmission();
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
        releaseAdmission();
        cancelTimers();
    }

    private String backendIdOf(Channel channel) {
        return backend == null ? "" : backend.id();
    }

    /**
     * Feeds one request outcome to the chosen backend's breaker. Never clears
     * {@code backend} — {@link #releaseInflight()} owns that, and recording an
     * outcome must not double as releasing the permit.
     */
    private void recordOutcome(boolean ok) {
        if (backend == null) {
            return;
        }
        if (ok) {
            backend.recordSuccess();
        } else {
            backend.recordFailure();
        }
    }

    /**
     * R-6: the admission permit is returned on every path, exactly once. Guarded
     * by the flag rather than released hopefully — a stray release would raise
     * the process-wide ceiling permanently, and the damage would only show up as
     * an overload that admission control mysteriously failed to stop.
     */
    private void releaseAdmission() {
        if (admitted) {
            admitted = false;
            context.admit().release();
        }
    }

    /** R-6: the in-flight permit is released on every path, exactly once. */
    private void releaseInflight() {
        if (backend != null) {
            backend.requestFinished();
            backend = null;
        }
    }

    // -------------------------------------------------------------- timeouts

    /**
     * Guards the phase where only the backend can act: the request is fully sent
     * and no response head has come back.
     *
     * <p>Deliberately <b>not</b> armed when the request starts (OPQ-009). Armed
     * there it spans the upload too, so a client legitimately pushing a gigabyte
     * over a slow link trips a limit whose entire purpose is to catch a backend
     * that has gone quiet. The two are different failures and now have different
     * timers.
     */
    private void armResponseTimeout(ChannelHandlerContext ctx) {
        cancelRequestTimeout();
        requestTimeout = ctx.executor().schedule(() -> {
            // A silent backend is exactly the outlier active probes are slowest
            // to catch: /healthz can keep answering while real requests hang.
            expire(ctx, "request_timeout");
        }, server.requestTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Guards the upload phase, and closes the hole that moving the response
     * timeout would otherwise open.
     *
     * <p>If the backend stops draining mid-upload, our own write buffer fills,
     * backpressure switches downstream reads off, and the idle timer is
     * suppressed on purpose because the client's silence is our doing (SUR-002).
     * With the response timeout no longer armed during the upload, nothing at all
     * would be watching, and the connection would hang until one side gave up.
     *
     * <p>The check re-arms itself against a timestamp instead of being reset on
     * every chunk: a 1 GB upload is a hundred thousand chunks, and a timer
     * operation per chunk would cost more than the transfer.
     */
    private void armStallCheck(ChannelHandlerContext ctx) {
        cancelStallCheck();
        if (server.stallTimeoutMs() <= 0) {
            return;
        }
        lastProgressNanos = System.nanoTime();
        scheduleStallCheck(ctx, server.stallTimeoutMs());
    }

    private void scheduleStallCheck(ChannelHandlerContext ctx, long delayMs) {
        stallCheck = ctx.executor().schedule(() -> {
            long quietMs = (System.nanoTime() - lastProgressNanos) / 1_000_000L;
            long remaining = server.stallTimeoutMs() - quietMs;
            if (remaining > 0) {
                scheduleStallCheck(ctx, remaining);
                return;
            }
            // Only our own backpressure counts as a stall. If reads are still on,
            // the silence is the client's and the idle timer owns it (408) — the
            // two guards partition the cases rather than racing for them.
            if (ctx.channel().config().isAutoRead()) {
                scheduleStallCheck(ctx, server.stallTimeoutMs());
                return;
            }
            expire(ctx, "upstream_stalled");
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Ends a request that ran out of time, whichever timer noticed. */
    private void expire(ChannelHandlerContext ctx, String reason) {
        upstreamReusable = false;   // a backend mid-message cannot be reused
        awaitingResponse = false;
        recordOutcome(false);
        finishRequest();
        drainInbox();
        Responses.sendAndClose(ctx.channel(), HttpResponseStatus.GATEWAY_TIMEOUT, reason);
    }

    private void cancelRequestTimeout() {
        if (requestTimeout != null) {
            requestTimeout.cancel(false);
            requestTimeout = null;
        }
    }

    private void cancelStallCheck() {
        if (stallCheck != null) {
            stallCheck.cancel(false);
            stallCheck = null;
        }
    }

    private void cancelTimers() {
        cancelRequestTimeout();
        cancelStallCheck();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.ALL_IDLE) {
            // Silence we caused by suppressing reads is not the client being idle;
            // timing it out would punish a peer that is blocked on us (SUR-002).
            // The stall check covers that case instead.
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
        cancelTimers();
        attempt = 0;
        awaitingResponse = false;
        shortCircuited = true;
        releaseAdmission();
        Responses.sendAndClose(ctx.channel(), status, reason);
    }

    /**
     * Refuses one request over the concurrency limit. No permit was taken, so
     * there is nothing to release; {@code shortCircuited} discards whatever the
     * codec still delivers for this request.
     */
    private void shed(ChannelHandlerContext ctx, HttpRequest req) {
        attempt = 0;
        shortCircuited = true;
        boolean bodiless = !HttpUtil.isTransferEncodingChunked(req)
                && HttpUtil.getContentLength(req, 0L) == 0L;
        Responses.shed(ctx.channel(), bodiless && HttpUtil.isKeepAlive(req));
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
        cancelTimers();
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
        cancelTimers();
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
