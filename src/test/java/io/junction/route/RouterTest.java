package io.junction.route;

import io.junction.config.RouteConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RouterTest {

    private static Router router(RouteConfig... routes) {
        return new Router(List.of(routes));
    }

    private static String resolve(Router r, String host, String uri) {
        RouteResult result = r.resolve(host, uri);
        return result instanceof RouteResult.Matched m ? m.pool() : "<none>";
    }

    /** Table-driven specificity: exact host beats wildcard, longer prefix beats shorter. */
    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "api.example.com, /v1/users, exact_v1",
            "api.example.com, /v1,       exact_v1",
            "api.example.com, /other,    exact_root",
            "other.host,      /v1/users, wildcard_v1",
            "other.host,      /nope,     wildcard_root",
    })
    void picksMostSpecificRoute(String host, String path, String expectedPool) {
        Router r = router(
                new RouteConfig("*", "/", "wildcard_root"),
                new RouteConfig("*", "/v1", "wildcard_v1"),
                new RouteConfig("api.example.com", "/", "exact_root"),
                new RouteConfig("api.example.com", "/v1", "exact_v1"));

        assertEquals(expectedPool, resolve(r, host, path));
    }

    /**
     * A prefix must end on a segment boundary. Without this, adding a service at
     * /api silently starts capturing an unrelated /apifoo.
     */
    @Test
    void prefixMatchesOnlyAtSegmentBoundary() {
        Router r = router(
                new RouteConfig("*", "/api", "api"),
                new RouteConfig("*", "/", "root"));

        assertEquals("api", resolve(r, "h", "/api"));
        assertEquals("api", resolve(r, "h", "/api/users"));
        assertEquals("root", resolve(r, "h", "/apifoo"));
    }

    @Test
    void ignoresPortInHostHeader() {
        Router r = router(new RouteConfig("api.example.com", "/", "api"));
        assertEquals("api", resolve(r, "api.example.com:8080", "/"));
        assertEquals("api", resolve(r, "API.EXAMPLE.COM", "/"));
    }

    @Test
    void ignoresPortAfterIpv6Literal() {
        Router r = router(new RouteConfig("[::1]", "/", "api"));
        assertEquals("api", resolve(r, "[::1]:8080", "/"));
        assertEquals("api", resolve(r, "[::1]", "/"));
    }

    @Test
    void stripsQueryStringBeforeMatching() {
        Router r = router(
                new RouteConfig("*", "/v1", "v1"),
                new RouteConfig("*", "/", "root"));
        assertEquals("v1", resolve(r, "h", "/v1/users?q=/other&x=1"));
    }

    @Test
    void unmatchedRequestReportsNoMatch() {
        Router r = router(new RouteConfig("api.example.com", "/", "api"));
        assertInstanceOf(RouteResult.NoMatch.class, r.resolve("elsewhere.com", "/"));
    }

    @Test
    void missingHostHeaderStillMatchesWildcard() {
        Router r = router(new RouteConfig("*", "/", "root"));
        assertEquals("root", resolve(r, null, "/"));
    }
}
