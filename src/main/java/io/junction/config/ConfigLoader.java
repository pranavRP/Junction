package io.junction.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates YAML into the immutable config graph.
 *
 * <p>Hand-rolled validation rather than an annotation-driven binder. The reason
 * is error quality: the operator gets {@code pools[0].backends[1].port must be
 * 1..65535, got 0} with a path, not a reflection stack trace. Startup failing
 * loudly with a useful message is a Phase 1 deliverable.
 *
 * <p>Unknown keys are rejected too. A silently ignored typo like {@code prt: 8080}
 * is worse than a hard failure — it starts, listens on the wrong port, and looks
 * fine until traffic arrives.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    public static ConfigResult load(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            return new ConfigResult.Invalid(List.of("cannot read " + file + ": " + e.getMessage()));
        }
        return parse(new StringReader(text));
    }

    public static ConfigResult load(InputStream in) {
        return parse(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }

    public static ConfigResult parse(Reader reader) {
        Object raw;
        try {
            // SafeConstructor: never instantiate arbitrary classes named in the
            // document. A config file is untrusted input the moment it is editable.
            LoaderOptions opts = new LoaderOptions();
            opts.setAllowDuplicateKeys(false);
            raw = new Yaml(new SafeConstructor(opts)).load(reader);
        } catch (RuntimeException e) {
            return new ConfigResult.Invalid(List.of("YAML syntax error: " + e.getMessage()));
        }
        if (raw == null) {
            return new ConfigResult.Invalid(List.of("config file is empty"));
        }
        if (!(raw instanceof Map<?, ?> top)) {
            return new ConfigResult.Invalid(List.of("top level must be a mapping, got "
                    + typeName(raw)));
        }

        var errors = new ArrayList<String>();
        var v = new Validator(errors);

        Map<String, Object> root = v.asMap("", top);
        v.rejectUnknownKeys("", root, Set.of("server", "pools", "routes"));

        ServerConfig server = parseServer(v, root.get("server"));
        List<PoolConfig> pools = parsePools(v, root.get("pools"));
        List<RouteConfig> routes = parseRoutes(v, root.get("routes"), pools);

        if (!errors.isEmpty()) {
            return new ConfigResult.Invalid(errors);
        }
        return new ConfigResult.Valid(new JunctionConfig(server, pools, routes));
    }

    // ---------------------------------------------------------------- server

    private static ServerConfig parseServer(Validator v, Object node) {
        ServerConfig d = ServerConfig.defaults();
        if (node == null) {
            return d; // every field has a defensible default; an absent block is fine
        }
        Map<String, Object> m = v.asMap("server", node);
        v.rejectUnknownKeys("server", m, Set.of(
                "port", "admin_port", "backlog", "max_connections", "max_header_bytes",
                "max_uri_length", "max_body_bytes", "idle_timeout_ms",
                "request_timeout_ms", "connect_timeout_ms"));

        int port = v.port("server.port", m.get("port"), d.port());
        int adminPort = v.port("server.admin_port", m.get("admin_port"), d.adminPort());
        if (port == adminPort) {
            v.error("server.admin_port must differ from server.port (both " + port + ")");
        }
        return new ServerConfig(
                port,
                adminPort,
                v.positiveInt("server.backlog", m.get("backlog"), d.backlog()),
                v.positiveInt("server.max_connections", m.get("max_connections"), d.maxConnections()),
                v.positiveInt("server.max_header_bytes", m.get("max_header_bytes"), d.maxHeaderBytes()),
                v.positiveInt("server.max_uri_length", m.get("max_uri_length"), d.maxUriLength()),
                v.positiveLong("server.max_body_bytes", m.get("max_body_bytes"), d.maxBodyBytes()),
                v.positiveLong("server.idle_timeout_ms", m.get("idle_timeout_ms"), d.idleTimeoutMs()),
                v.positiveLong("server.request_timeout_ms", m.get("request_timeout_ms"), d.requestTimeoutMs()),
                v.positiveLong("server.connect_timeout_ms", m.get("connect_timeout_ms"), d.connectTimeoutMs()));
    }

    // ----------------------------------------------------------------- pools

    private static List<PoolConfig> parsePools(Validator v, Object node) {
        var out = new ArrayList<PoolConfig>();
        List<Object> list = v.asList("pools", node);
        if (list.isEmpty()) {
            v.error("pools must contain at least one pool");
            return out;
        }
        var seenPools = new HashSet<String>();
        for (int i = 0; i < list.size(); i++) {
            String path = "pools[" + i + "]";
            Map<String, Object> m = v.asMap(path, list.get(i));
            v.rejectUnknownKeys(path, m, Set.of("name", "backends"));

            String name = v.nonBlank(path + ".name", m.get("name"));
            if (name != null && !seenPools.add(name)) {
                v.error(path + ".name duplicates an earlier pool: '" + name + "'");
            }
            out.add(new PoolConfig(
                    name == null ? "<invalid>" : name,
                    parseBackends(v, path, m.get("backends"))));
        }
        return out;
    }

    private static List<BackendConfig> parseBackends(Validator v, String parent, Object node) {
        var out = new ArrayList<BackendConfig>();
        List<Object> list = v.asList(parent + ".backends", node);
        if (list.isEmpty()) {
            v.error(parent + ".backends must contain at least one backend");
            return out;
        }
        var seenIds = new HashSet<String>();
        for (int i = 0; i < list.size(); i++) {
            String path = parent + ".backends[" + i + "]";
            Map<String, Object> m = v.asMap(path, list.get(i));
            v.rejectUnknownKeys(path, m, Set.of("id", "host", "port", "weight"));

            String id = v.nonBlank(path + ".id", m.get("id"));
            if (id != null && !seenIds.add(id)) {
                v.error(path + ".id duplicates an earlier backend: '" + id + "'");
            }
            out.add(new BackendConfig(
                    id == null ? "<invalid>" : id,
                    orDefault(v.nonBlank(path + ".host", m.get("host")), "<invalid>"),
                    v.port(path + ".port", m.get("port"), 0),
                    v.weight(path + ".weight", m.get("weight"))));
        }
        return out;
    }

    // ---------------------------------------------------------------- routes

    private static List<RouteConfig> parseRoutes(Validator v, Object node, List<PoolConfig> pools) {
        var out = new ArrayList<RouteConfig>();
        List<Object> list = v.asList("routes", node);
        if (list.isEmpty()) {
            v.error("routes must contain at least one route");
            return out;
        }
        Set<String> poolNames = new HashSet<>();
        for (PoolConfig p : pools) {
            poolNames.add(p.name());
        }
        for (int i = 0; i < list.size(); i++) {
            String path = "routes[" + i + "]";
            Map<String, Object> m = v.asMap(path, list.get(i));
            v.rejectUnknownKeys(path, m, Set.of("host", "prefix", "pool"));

            String host = orDefault(v.nonBlank(path + ".host", m.get("host")), "*");
            String prefix = orDefault(v.nonBlank(path + ".prefix", m.get("prefix")), "/");
            if (!prefix.startsWith("/")) {
                v.error(path + ".prefix must start with '/', got '" + prefix + "'");
            }
            String pool = v.nonBlank(path + ".pool", m.get("pool"));
            if (pool != null && !poolNames.contains(pool)) {
                v.error(path + ".pool refers to unknown pool '" + pool + "' (known: "
                        + (poolNames.isEmpty() ? "none" : String.join(", ", poolNames)) + ")");
            }
            out.add(new RouteConfig(host, prefix, pool == null ? "<invalid>" : pool));
        }
        return out;
    }

    private static String orDefault(String s, String def) {
        return s == null ? def : s;
    }

    private static String typeName(Object o) {
        if (o == null) return "null";
        if (o instanceof Map) return "a mapping";
        if (o instanceof List) return "a list";
        return o.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    /** Accumulates errors instead of throwing, so one pass reports every problem. */
    private static final class Validator {
        private final List<String> errors;

        Validator(List<String> errors) {
            this.errors = errors;
        }

        void error(String msg) {
            errors.add(msg);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> asMap(String path, Object node) {
            if (node instanceof Map<?, ?> m) {
                var copy = new LinkedHashMap<String, Object>();
                m.forEach((k, val) -> copy.put(String.valueOf(k), val));
                return copy;
            }
            error((path.isEmpty() ? "top level" : path) + " must be a mapping, got " + typeName(node));
            return new LinkedHashMap<>();
        }

        List<Object> asList(String path, Object node) {
            if (node == null) {
                error(path + " is required");
                return List.of();
            }
            if (node instanceof List<?> l) {
                return new ArrayList<>(l);
            }
            error(path + " must be a list, got " + typeName(node));
            return List.of();
        }

        void rejectUnknownKeys(String path, Map<String, Object> m, Set<String> known) {
            for (String k : m.keySet()) {
                if (!known.contains(k)) {
                    error((path.isEmpty() ? "top level" : path) + " has unknown key '" + k
                            + "' (known: " + String.join(", ", new java.util.TreeSet<>(known)) + ")");
                }
            }
        }

        String nonBlank(String path, Object node) {
            if (node == null) {
                error(path + " is required");
                return null;
            }
            String s = String.valueOf(node).trim();
            if (s.isEmpty()) {
                error(path + " must not be blank");
                return null;
            }
            return s;
        }

        long number(String path, Object node, long def, boolean[] present) {
            if (node == null) {
                present[0] = false;
                return def;
            }
            present[0] = true;
            if (node instanceof Number n) {
                return n.longValue();
            }
            try {
                return Long.parseLong(String.valueOf(node).trim());
            } catch (NumberFormatException e) {
                error(path + " must be a number, got '" + node + "'");
                present[0] = false;
                return def;
            }
        }

        int port(String path, Object node, int def) {
            boolean[] present = new boolean[1];
            long val = number(path, node, def, present);
            if (!present[0]) {
                if (node == null && def == 0) {
                    error(path + " is required");
                }
                return def;
            }
            if (val < 1 || val > 65535) {
                error(path + " must be 1..65535, got " + val);
                return def;
            }
            return (int) val;
        }

        int weight(String path, Object node) {
            if (node == null) {
                return 100; // absent weight means "normal share"
            }
            boolean[] present = new boolean[1];
            long val = number(path, node, 100, present);
            if (!present[0]) {
                return 100;
            }
            if (val < 0 || val > 1000) {
                error(path + " must be 0..1000 (0 = drain), got " + val);
                return 100;
            }
            return (int) val;
        }

        int positiveInt(String path, Object node, int def) {
            long val = positiveLong(path, node, def);
            if (val > Integer.MAX_VALUE) {
                error(path + " must be <= " + Integer.MAX_VALUE + ", got " + val);
                return def;
            }
            return (int) val;
        }

        long positiveLong(String path, Object node, long def) {
            boolean[] present = new boolean[1];
            long val = number(path, node, def, present);
            if (!present[0]) {
                return def;
            }
            if (val <= 0) {
                error(path + " must be > 0, got " + val);
                return def;
            }
            return val;
        }
    }
}
