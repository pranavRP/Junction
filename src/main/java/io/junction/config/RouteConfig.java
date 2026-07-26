package io.junction.config;

/**
 * Host + path-prefix match to a pool name (FR-1.2).
 *
 * @param host   exact Host header match, or {@code "*"} for any
 * @param prefix path prefix, must start with {@code /}
 * @param pool   name of the target pool; validated to exist at load time
 */
public record RouteConfig(String host, String prefix, String pool) {

    public boolean matchesAnyHost() {
        return "*".equals(host);
    }
}
