package io.junction.http;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;

/**
 * Builds the upstream pipeline for pooled connections.
 *
 * <p>Exists so {@code io.junction.pool} can create connections without knowing
 * anything about HTTP: the pool takes a supplier of initialisers and stays a pure
 * socket cache, which keeps it from depending upward on this package (R-11).
 */
public final class ProxyBackendHandlerFactory {

    private ProxyBackendHandlerFactory() {}

    public static ChannelInitializer<SocketChannel> newInitializer(int maxUriLength, int maxHeaderBytes) {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new HttpClientCodec(maxUriLength, maxHeaderBytes, 8192));
                // Starts detached; whichever request acquires this connection
                // attaches itself as the owner.
                ch.pipeline().addLast(new ProxyBackendHandler());
            }
        };
    }
}
