package io.junction;

import io.junction.config.ConfigLoader;
import io.junction.config.ConfigResult;
import io.junction.config.JunctionConfig;
import io.junction.net.JunctionServer;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point.
 *
 * <p>Startup either binds with a fully valid config or exits non-zero having
 * printed every problem. There is no partially-applied middle state (R-8) and no
 * "starts anyway with defaults" behaviour — a proxy that silently listens on the
 * wrong port is worse than one that refuses to start.
 */
public final class Junction {

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0
                ? args[0]
                : System.getenv().getOrDefault("JUNCTION_CONFIG", "junction.yaml"));

        if (!Files.isReadable(configPath)) {
            System.err.println("junction: cannot read config file: " + configPath.toAbsolutePath());
            System.err.println("  pass a path as the first argument, or set JUNCTION_CONFIG");
            System.exit(1);
        }

        ConfigResult result = ConfigLoader.load(configPath);
        if (result instanceof ConfigResult.Invalid invalid) {
            System.err.println("junction: refusing to start — " + configPath.toAbsolutePath());
            System.err.println(invalid.message());
            System.exit(1);
            return;
        }

        JunctionConfig config = ((ConfigResult.Valid) result).config();
        JunctionServer server = new JunctionServer(config);
        server.start();

        System.out.println("[junction] listening on :" + server.boundPort()
                + "  pools=" + config.pools().size()
                + "  routes=" + config.routes().size());

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "junction-shutdown"));
        server.awaitShutdown();
    }

    private Junction() {}
}
