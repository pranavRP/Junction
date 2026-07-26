package io.junction.http;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderRewriterTest {

    private static HttpRequest request() {
        return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    }

    private static HttpResponse response() {
        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
            "Proxy-Connection", "TE", "Trailer", "Upgrade"})
    void stripsHopByHopHeaders(String name) {
        HttpRequest req = request();
        req.headers().set(name, "value");

        HeaderRewriter.forRequest(req, "1.2.3.4", "http", "rid");

        assertFalse(req.headers().contains(name), name + " must not be forwarded");
    }

    /**
     * RFC 9110 §7.6.1: the Connection header nominates further headers that are
     * themselves connection-scoped. Missing this is how a custom header leaks to
     * the next hop.
     */
    @Test
    void stripsHeadersNominatedByConnectionHeader() {
        HttpRequest req = request();
        req.headers().set(HttpHeaderNames.CONNECTION, "keep-alive, X-Custom-Hop");
        req.headers().set("X-Custom-Hop", "secret");
        req.headers().set("X-Kept", "keep me");

        HeaderRewriter.forRequest(req, "1.2.3.4", "http", "rid");

        assertFalse(req.headers().contains("X-Custom-Hop"));
        assertEquals("keep me", req.headers().get("X-Kept"));
    }

    @Test
    void appendsToExistingForwardedForChain() {
        HttpRequest req = request();
        req.headers().set(HeaderRewriter.X_FORWARDED_FOR, "203.0.113.1");

        HeaderRewriter.forRequest(req, "10.0.0.9", "http", "rid");

        assertEquals("203.0.113.1, 10.0.0.9", req.headers().get(HeaderRewriter.X_FORWARDED_FOR));
    }

    @Test
    void generatesRequestIdOnlyWhenClientDidNotSupplyOne() {
        HttpRequest generated = request();
        HeaderRewriter.forRequest(generated, "1.2.3.4", "http", "generated-id");
        assertEquals("generated-id", generated.headers().get(HeaderRewriter.X_REQUEST_ID));

        HttpRequest supplied = request();
        supplied.headers().set(HeaderRewriter.X_REQUEST_ID, "client-id");
        HeaderRewriter.forRequest(supplied, "1.2.3.4", "http", "generated-id");
        assertEquals("client-id", supplied.headers().get(HeaderRewriter.X_REQUEST_ID));
    }

    @Test
    void setsForwardedProto() {
        HttpRequest req = request();
        HeaderRewriter.forRequest(req, "1.2.3.4", "http", "rid");
        assertEquals("http", req.headers().get(HeaderRewriter.X_FORWARDED_PROTO));
    }

    /**
     * Transfer-Encoding is hop-by-hop, so it is stripped — but we are streaming a
     * body of unknown length, so chunked framing must be restated rather than lost.
     */
    @Test
    void preservesChunkedFramingAcrossStripping() {
        HttpRequest req = request();
        HttpUtil.setTransferEncodingChunked(req, true);

        HeaderRewriter.forRequest(req, "1.2.3.4", "http", "rid");

        assertTrue(HttpUtil.isTransferEncodingChunked(req), "chunked framing must survive");
    }

    @Test
    void preservesContentLengthFraming() {
        HttpRequest req = request();
        req.headers().set(HttpHeaderNames.CONTENT_LENGTH, 1234);

        HeaderRewriter.forRequest(req, "1.2.3.4", "http", "rid");

        assertEquals(1234L, HttpUtil.getContentLength(req, -1L));
        assertFalse(HttpUtil.isTransferEncodingChunked(req));
    }

    @Test
    void requestAlwaysAsksUpstreamToKeepAlive() {
        HttpRequest req = request();
        req.headers().set(HttpHeaderNames.CONNECTION, "close");

        HeaderRewriter.forRequest(req, "1.2.3.4", "http", "rid");

        assertTrue(HttpUtil.isKeepAlive(req),
                "upstream connection lifetime is ours to manage, not the client's");
    }

    @Test
    void responseConnectionHeaderFollowsDownstreamKeepAlive() {
        HttpResponse alive = response();
        HeaderRewriter.forResponse(alive, true, "rid");
        assertTrue(HttpUtil.isKeepAlive(alive));

        HttpResponse closing = response();
        HeaderRewriter.forResponse(closing, false, "rid");
        assertFalse(HttpUtil.isKeepAlive(closing));
    }

    @Test
    void responseEchoesRequestId() {
        HttpResponse resp = response();
        HeaderRewriter.forResponse(resp, true, "rid-42");
        assertEquals("rid-42", resp.headers().get(HeaderRewriter.X_REQUEST_ID));
    }

    @Test
    void responsePreservesChunkedFraming() {
        HttpResponse resp = response();
        HttpUtil.setTransferEncodingChunked(resp, true);
        HeaderRewriter.forResponse(resp, true, "rid");
        assertTrue(HttpUtil.isTransferEncodingChunked(resp));
    }
}
