package io.junction.http;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;

/**
 * Extracts the consistent-hash key named by a pool's {@code hash_key} spec
 * ({@code header:X-Session-Id} or {@code cookie:sid}).
 *
 * <p>Lives here rather than in {@code balance} so that package stays free of
 * HTTP types (R-11): the balancer receives an already-extracted string and is
 * testable without a request object.
 *
 * <p>A missing key yields an empty string, which the balancer spreads randomly
 * rather than hashing to a single unlucky backend.
 */
final class HashKeys {

    private HashKeys() {}

    static String extract(HttpRequest request, String spec) {
        if (spec == null || spec.isEmpty()) {
            return "";
        }
        if (spec.startsWith("header:")) {
            String value = request.headers().get(spec.substring("header:".length()));
            return value == null ? "" : value.trim();
        }
        if (spec.startsWith("cookie:")) {
            return cookieValue(request, spec.substring("cookie:".length()));
        }
        return "";
    }

    /**
     * Minimal cookie lookup — deliberately not a full RFC 6265 parser, because
     * the value is only ever used as hash input. A mis-parsed cookie costs
     * affinity, never correctness.
     */
    private static String cookieValue(HttpRequest request, String name) {
        String header = request.headers().get(HttpHeaderNames.COOKIE);
        if (header == null) {
            return "";
        }
        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(name)) {
                return pair.substring(eq + 1).trim();
            }
        }
        return "";
    }
}
