package io.junction.route;

import io.junction.config.RouteConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Resolves {@code Host} + path prefix to a pool name (FR-1.2).
 *
 * <p><b>Why a sorted list and not a trie</b> (OPQ-003): with fewer than ~20
 * routes a linear scan over a length-sorted array beats a trie on cache locality
 * and is a fraction of the code. The trie is the optimisation you reach for after
 * measuring, not before. If a benchmark ever shows this in the profile, the
 * interface here does not change — only the internals do.
 *
 * <p>Immutable and safe to read from any event loop without synchronisation
 * (R-3). Reload replaces the whole instance rather than mutating this one.
 */
public final class Router {

    private final List<Entry> entries;

    public Router(List<RouteConfig> routes) {
        var list = new ArrayList<Entry>(routes.size());
        for (RouteConfig r : routes) {
            list.add(new Entry(
                    r.matchesAnyHost() ? null : r.host().toLowerCase(Locale.ROOT),
                    r.prefix(),
                    r.pool()));
        }
        // Most specific first: an exact host beats a wildcard, and among equals a
        // longer prefix beats a shorter one. Sorting once at construction keeps
        // resolve() a plain scan with no per-request ordering work.
        list.sort(Comparator
                .comparing((Entry e) -> e.host == null)          // false (exact) first
                .thenComparing(e -> -e.prefix.length()));
        this.entries = List.copyOf(list);
    }

    /**
     * @param hostHeader raw {@code Host} header value, may be null or carry a port
     * @param uri        raw request target, may carry a query string
     */
    public RouteResult resolve(String hostHeader, String uri) {
        String host = normaliseHost(hostHeader);
        String path = pathOf(uri);

        for (Entry e : entries) {
            if (e.host != null && !e.host.equals(host)) {
                continue;
            }
            if (matchesPrefix(path, e.prefix)) {
                return new RouteResult.Matched(e.pool);
            }
        }
        return new RouteResult.NoMatch("no_route");
    }

    /**
     * A prefix must match at a segment boundary. Without this check the prefix
     * {@code /api} would capture {@code /apifoo}, which is a routing bug that
     * only shows up once someone adds a similarly-named service.
     */
    private static boolean matchesPrefix(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        if (prefix.endsWith("/") || path.length() == prefix.length()) {
            return true;
        }
        return path.charAt(prefix.length()) == '/';
    }

    private static String normaliseHost(String hostHeader) {
        if (hostHeader == null) {
            return "";
        }
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        // Strip the port, but not from an IPv6 literal's inner colons.
        int close = h.lastIndexOf(']');
        int colon = h.lastIndexOf(':');
        if (colon > close) {
            h = h.substring(0, colon);
        }
        return h;
    }

    private static String pathOf(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "/";
        }
        int cut = uri.indexOf('?');
        if (cut < 0) {
            cut = uri.indexOf('#');
        }
        String path = cut < 0 ? uri : uri.substring(0, cut);
        return path.isEmpty() ? "/" : path;
    }

    private record Entry(String host, String prefix, String pool) {}
}
