package io.junction.pool;

import io.junction.config.UpstreamPoolConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Idle upstream sockets, partitioned per EventLoop then per backend (design.md §3).
 *
 * <p><b>Why per-EventLoop.</b> The partition is the entire concurrency design: a
 * connection taken from this loop's deque is already bound to this loop, so R-4
 * holds by construction and acquire/release need no locks. A single shared pool
 * would either need a lock on the hottest path in the proxy, or hand back a
 * channel belonging to another loop and force a cross-thread handoff on every
 * write.
 *
 * <p><b>LIFO, not FIFO.</b> Returning the most recently used connection keeps a
 * small working set hot and lets the rest age out to idle TTL. FIFO cycles every
 * connection evenly, so all of them stay alive — worse for memory here and worse
 * for cache locality on the backend.
 *
 * <p><b>Staleness</b> is a race that cannot be fully closed: the peer may drop a
 * pooled connection at any moment. Three defences, in order of cheapness — an
 * idle TTL shorter than the backend's keep-alive (see
 * {@link UpstreamPoolConfig}), an {@code isActive} check on acquire, and a close
 * listener that evicts a connection the moment the peer closes it.
 */
public final class UpstreamPool {

    private final UpstreamPoolConfig config;
    private final Clock clock;
    private final Supplier<ChannelInitializer<SocketChannel>> initializerFactory;

    /** Outer map is concurrent; every inner structure is confined to its loop. */
    private final Map<EventLoop, Map<String, Deque<Pooled>>> idle = new ConcurrentHashMap<>();

    public UpstreamPool(UpstreamPoolConfig config,
                        Clock clock,
                        Supplier<ChannelInitializer<SocketChannel>> initializerFactory) {
        this.config = config;
        this.clock = clock;
        this.initializerFactory = initializerFactory;
    }

    /**
     * Hands back a connected channel on {@code loop}, reusing an idle one when
     * possible. The returned future always completes on {@code loop}.
     */
    public Future<Channel> acquire(EventLoop loop, String backendId, String host, int port) {
        Deque<Pooled> queue = queueFor(loop, backendId);

        Pooled candidate;
        while ((candidate = queue.pollLast()) != null) {   // LIFO: warmest first
            candidate.cancelEviction();
            if (isUsable(candidate)) {
                return loop.newSucceededFuture(candidate.channel());
            }
            candidate.channel().close();
        }
        return connect(loop, host, port);
    }

    /**
     * Returns a channel to the pool, or closes it when the pool is full or the
     * channel is no longer reusable.
     *
     * <p>Must be called from {@code loop}: the deque is loop-confined.
     */
    public void release(EventLoop loop, String backendId, Channel channel) {
        if (channel == null) {
            return;
        }
        if (!channel.isActive()) {
            channel.close();
            return;
        }
        Deque<Pooled> queue = queueFor(loop, backendId);
        if (queue.size() >= config.maxIdlePerBackend()) {
            // R-5: the pool is bounded. Over the bound, closing is correct —
            // keeping it would trade a socket we do not need for one we might.
            channel.close();
            return;
        }
        Pooled pooled = new Pooled(channel, clock.millis());
        // An idle pooled connection the peer closes must not sit here waiting to
        // be handed to a request. Evict on close rather than discover it later.
        pooled.armEviction(queue);
        queue.addLast(pooled);
    }

    /** Discards every idle connection for a backend — used when it leaves the pool. */
    public void evictAll(EventLoop loop, String backendId) {
        Deque<Pooled> queue = queueFor(loop, backendId);
        Pooled p;
        while ((p = queue.pollFirst()) != null) {
            p.cancelEviction();
            p.channel().close();
        }
    }

    public int idleCount(EventLoop loop, String backendId) {
        return queueFor(loop, backendId).size();
    }

    private boolean isUsable(Pooled pooled) {
        if (!pooled.channel().isActive()) {
            return false;
        }
        return clock.millis() - pooled.pooledAt() < config.idleTtlMs();
    }

    private Deque<Pooled> queueFor(EventLoop loop, String backendId) {
        return idle.computeIfAbsent(loop, l -> new HashMap<>())
                .computeIfAbsent(backendId, id -> new ArrayDeque<>());
    }

    private Future<Channel> connect(EventLoop loop, String host, int port) {
        Promise<Channel> promise = loop.newPromise();
        Bootstrap bootstrap = new Bootstrap()
                // R-4: bound to the caller's loop, so the channel this returns is
                // already on the right thread for the downstream connection.
                .group(loop)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.maxConnectMs())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                .handler(initializerFactory.get());

        bootstrap.connect(new InetSocketAddress(host, port))
                .addListener((ChannelFuture f) -> {
                    if (f.isSuccess()) {
                        promise.trySuccess(f.channel());
                    } else {
                        promise.tryFailure(f.cause());
                    }
                });
        return promise;
    }

    /** An idle connection and the moment it went idle. */
    private static final class Pooled {
        private final Channel channel;
        private final long pooledAt;
        private io.netty.channel.ChannelFutureListener evictor;

        Pooled(Channel channel, long pooledAt) {
            this.channel = channel;
            this.pooledAt = pooledAt;
        }

        Channel channel() {
            return channel;
        }

        long pooledAt() {
            return pooledAt;
        }

        void armEviction(Deque<Pooled> queue) {
            evictor = f -> queue.remove(this);
            channel.closeFuture().addListener(evictor);
        }

        /** Removes the listener once in use, so it cannot evict a live request's channel. */
        void cancelEviction() {
            if (evictor != null) {
                channel.closeFuture().removeListener(evictor);
                evictor = null;
            }
        }
    }
}
