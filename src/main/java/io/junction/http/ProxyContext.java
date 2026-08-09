package io.junction.http;

import io.junction.backend.PoolRegistry;
import io.junction.config.ServerConfig;
import io.junction.pool.UpstreamPool;
import io.junction.route.Router;

import java.util.Map;

/**
 * Everything a request handler needs from one config generation, passed as a
 * single immutable value.
 *
 * <p>Constructor injection all the way down (R-12/R-14): no statics, no
 * singletons, no lookups. A reload builds a new context and new handlers rather
 * than mutating this one, so requests in flight keep the graph they started with.
 *
 * @param connectionPools one upstream pool per backend pool, keyed by pool name;
 *                        separate because pool sizing is per-pool config
 */
public record ProxyContext(
        Router router,
        PoolRegistry pools,
        Map<String, UpstreamPool> connectionPools,
        ServerConfig server) {

    public ProxyContext {
        connectionPools = Map.copyOf(connectionPools);
    }
}
