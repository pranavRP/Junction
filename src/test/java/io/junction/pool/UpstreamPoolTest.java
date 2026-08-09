package io.junction.pool;

import io.junction.chaos.ChaosBackend;
import io.junction.config.UpstreamPoolConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamPoolTest {

    private ChaosBackend backend;
    private EventLoopGroup group;
    private EventLoop loop;
    private TestClock clock;

    /** Hand-driven clock so idle TTL is asserted instantly, not by sleeping (R-23). */
    private static final class TestClock extends Clock {
        private long millis = 1_000_000L;

        void advance(long by) {
            millis += by;
        }

        @Override public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        backend = new ChaosBackend(0, "b1");
        backend.start();
        group = new NioEventLoopGroup(1);
        loop = group.next();
        clock = new TestClock();
    }

    @AfterEach
    void tearDown() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        backend.stop();
    }

    private UpstreamPool pool(UpstreamPoolConfig config) {
        return new UpstreamPool(config, clock, () -> new ChannelInitializer<SocketChannel>() {
            @Override protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new HttpClientCodec());
            }
        });
    }

    private Channel acquire(UpstreamPool pool) throws Exception {
        Future<Channel> f = pool.acquire(loop, "b1", "127.0.0.1", backend.boundPort());
        return f.get(5, TimeUnit.SECONDS);
    }

    /** Run on the loop, because release and the deques are loop-confined. */
    private void onLoop(Runnable action) throws Exception {
        loop.submit(action).get(5, TimeUnit.SECONDS);
    }

    @Test
    void reusesAReleasedConnection() throws Exception {
        UpstreamPool pool = pool(UpstreamPoolConfig.defaults());

        Channel first = acquire(pool);
        onLoop(() -> pool.release(loop, "b1", first));

        Channel second = acquire(pool);
        assertSame(first, second, "a released, live connection must be reused, not reconnected");
    }

    @Test
    void connectsFreshWhenThePoolIsEmpty() throws Exception {
        UpstreamPool pool = pool(UpstreamPoolConfig.defaults());

        Channel first = acquire(pool);
        Channel second = acquire(pool);   // first never released

        assertNotSame(first, second);
        assertTrue(first.isActive() && second.isActive());
    }

    /** LIFO keeps a small set of connections hot instead of cycling all of them. */
    @Test
    void handsBackTheMostRecentlyReleasedConnection() throws Exception {
        UpstreamPool pool = pool(UpstreamPoolConfig.defaults());

        Channel a = acquire(pool);
        Channel b = acquire(pool);
        onLoop(() -> {
            pool.release(loop, "b1", a);
            pool.release(loop, "b1", b);
        });

        assertSame(b, acquire(pool), "LIFO: the warmest connection comes back first");
    }

    @Test
    void discardsConnectionsPastTheIdleTtl() throws Exception {
        UpstreamPool pool = pool(new UpstreamPoolConfig(64, 1_000, 5_000));

        Channel first = acquire(pool);
        onLoop(() -> pool.release(loop, "b1", first));

        clock.advance(5_001);

        Channel second = acquire(pool);
        assertNotSame(first, second, "a connection past its TTL must not be handed out");
    }

    @Test
    void keepsConnectionsWithinTheIdleTtl() throws Exception {
        UpstreamPool pool = pool(new UpstreamPoolConfig(64, 1_000, 5_000));

        Channel first = acquire(pool);
        onLoop(() -> pool.release(loop, "b1", first));

        clock.advance(4_999);

        assertSame(first, acquire(pool));
    }

    @Test
    void closesConnectionsBeyondMaxIdle() throws Exception {
        UpstreamPool pool = pool(new UpstreamPoolConfig(2, 1_000, 30_000));

        Channel a = acquire(pool);
        Channel b = acquire(pool);
        Channel c = acquire(pool);
        onLoop(() -> {
            pool.release(loop, "b1", a);
            pool.release(loop, "b1", b);
            pool.release(loop, "b1", c);   // over the bound
        });

        onLoop(() -> assertEquals(2, pool.idleCount(loop, "b1"), "pool must stay bounded"));
        assertFalse(c.isActive(), "the surplus connection must be closed, not dropped on the floor");
    }

    /** A pooled connection the peer closes must leave the pool immediately. */
    @Test
    void evictsAPooledConnectionWhenThePeerClosesIt() throws Exception {
        UpstreamPool pool = pool(UpstreamPoolConfig.defaults());

        Channel first = acquire(pool);
        onLoop(() -> pool.release(loop, "b1", first));
        onLoop(() -> assertEquals(1, pool.idleCount(loop, "b1")));

        first.close().sync();

        onLoop(() -> assertEquals(0, pool.idleCount(loop, "b1"),
                "a closed connection must not linger in the pool waiting to be handed out"));
    }

    @Test
    void neverPoolsAnAlreadyClosedConnection() throws Exception {
        UpstreamPool pool = pool(UpstreamPoolConfig.defaults());

        Channel first = acquire(pool);
        first.close().sync();
        onLoop(() -> pool.release(loop, "b1", first));

        onLoop(() -> assertEquals(0, pool.idleCount(loop, "b1")));
    }

    @Test
    void keepsBackendsInSeparateQueues() throws Exception {
        UpstreamPool pool = pool(UpstreamPoolConfig.defaults());

        Channel first = acquire(pool);
        onLoop(() -> pool.release(loop, "b1", first));

        onLoop(() -> {
            assertEquals(1, pool.idleCount(loop, "b1"));
            assertEquals(0, pool.idleCount(loop, "b2"), "backends must not share a queue");
        });
    }

    @Test
    void evictAllDropsEveryIdleConnectionForABackend() throws Exception {
        UpstreamPool pool = pool(UpstreamPoolConfig.defaults());

        Channel a = acquire(pool);
        Channel b = acquire(pool);
        onLoop(() -> {
            pool.release(loop, "b1", a);
            pool.release(loop, "b1", b);
            pool.evictAll(loop, "b1");
            assertEquals(0, pool.idleCount(loop, "b1"));
        });

        assertFalse(a.isActive());
        assertFalse(b.isActive());
    }

    @Test
    void failedConnectSurfacesAsAFailedFuture() throws Exception {
        UpstreamPool pool = pool(new UpstreamPoolConfig(64, 200, 30_000));

        Future<Channel> f = pool.acquire(loop, "dead", "127.0.0.1", 1);
        f.await(5, TimeUnit.SECONDS);

        assertFalse(f.isSuccess(), "connecting to a closed port must fail, not hang");
    }
}
