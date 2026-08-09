package io.junction.net;

import io.junction.backend.HealthChecker;
import io.junction.backend.PoolRegistry;
import io.junction.config.JunctionConfig;
import io.junction.config.PoolConfig;
import io.junction.config.ServerConfig;
import io.junction.http.ProxyBackendHandlerFactory;
import io.junction.http.ProxyContext;
import io.junction.http.ProxyFrontendHandler;
import io.junction.pool.UpstreamPool;
import io.junction.route.Router;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Binds the data-path listener and owns the object graph for one config
 * generation: pools, balancers, connection pools, and the health checker.
 *
 * <p>One acceptor thread, N workers. Each client connection is pinned to a worker
 * for its lifetime, and its upstream connections come from that same loop's
 * partition of the pool (R-4).
 */
public final class JunctionServer {

    private final JunctionConfig config;
    private final Clock clock;
    private final Router router;
    private final PoolRegistry pools;
    private final Map<String, UpstreamPool> connectionPools = new LinkedHashMap<>();

    private EventLoopGroup acceptor;
    private EventLoopGroup workers;
    private Channel listenChannel;
    private ConnectionLimitHandler connectionLimit;
    private HealthChecker healthChecker;

    public JunctionServer(JunctionConfig config) {
        this(config, Clock.systemUTC());
    }

    public JunctionServer(JunctionConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
        this.router = new Router(config.routes());
        this.pools = PoolRegistry.create(config, clock);

        ServerConfig s = config.server();
        for (PoolConfig pool : config.pools()) {
            // One upstream pool per backend pool: sizing and TTL are per-pool
            // config, and mixing pools behind one bound would let a busy pool
            // starve a quiet one.
            connectionPools.put(pool.name(), new UpstreamPool(
                    pool.pool(), clock,
                    () -> ProxyBackendHandlerFactory.newInitializer(
                            s.maxUriLength(), s.maxHeaderBytes())));
        }
    }

    public void start() throws InterruptedException {
        ServerConfig s = config.server();
        acceptor = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        connectionLimit = new ConnectionLimitHandler(s.maxConnections());

        ProxyContext context = new ProxyContext(router, pools, connectionPools, s);

        ServerBootstrap b = new ServerBootstrap()
                .group(acceptor, workers)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, s.backlog())
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                // DEC-005: these watermarks are the trigger points for the
                // autoRead valve. Tuned in Phase 4.
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(32 * 1024, 64 * 1024))
                // §12.4: a client may half-close after its request and still be
                // waiting for the response; treating FIN as an abort breaks it.
                .childOption(ChannelOption.ALLOW_HALF_CLOSURE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(connectionLimit)
                                .addLast(new HttpServerCodec(
                                        s.maxUriLength(), s.maxHeaderBytes(), 8192))
                                .addLast(new IdleStateHandler(
                                        0, 0, s.idleTimeoutMs(), TimeUnit.MILLISECONDS))
                                .addLast(new LimitsHandler())
                                .addLast(new ProxyFrontendHandler(context));
                    }
                });

        listenChannel = b.bind(new InetSocketAddress(s.port())).sync().channel();

        healthChecker = new HealthChecker(pools);
        healthChecker.start();
    }

    public int boundPort() {
        return ((InetSocketAddress) listenChannel.localAddress()).getPort();
    }

    public int activeConnections() {
        return connectionLimit == null ? 0 : connectionLimit.activeConnections();
    }

    public PoolRegistry pools() {
        return pools;
    }

    public void awaitShutdown() throws InterruptedException {
        listenChannel.closeFuture().sync();
    }

    /** Phase 6 replaces this with a real drain; Phase 2 needs only a clean stop. */
    public void stop() {
        if (healthChecker != null) {
            healthChecker.close();
        }
        if (listenChannel != null) {
            listenChannel.close().syncUninterruptibly();
        }
        if (acceptor != null) {
            acceptor.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (workers != null) {
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
    }
}
