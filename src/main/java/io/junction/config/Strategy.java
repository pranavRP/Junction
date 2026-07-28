package io.junction.config;

import java.util.Locale;
import java.util.Optional;

/** Backend selection strategy for a pool (FR-2.1). */
public enum Strategy {

    /** Smooth weighted round robin — spreads weight, does not emit runs. */
    ROUND_ROBIN("round_robin"),

    /** Fewest in-flight wins. Simple, but every loop sees the same minimum. */
    LEAST_CONNECTIONS("least_connections"),

    /** Two at random, take the less loaded. The sane default (see design.md §2.2). */
    P2C("p2c"),

    /** Ring hashing with bounded load — affinity without hot-spotting. */
    CONSISTENT_HASH("consistent_hash");

    private final String key;

    Strategy(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static Optional<Strategy> fromKey(String s) {
        String want = s.trim().toLowerCase(Locale.ROOT);
        for (Strategy v : values()) {
            if (v.key.equals(want)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    public static String knownKeys() {
        var sb = new StringBuilder();
        for (Strategy v : values()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(v.key);
        }
        return sb.toString();
    }
}
