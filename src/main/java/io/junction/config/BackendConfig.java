package io.junction.config;

/** One upstream host. {@code weight == 0} means drain (FR-2.2), honoured from Phase 2. */
public record BackendConfig(String id, String host, int port, int weight) {}
