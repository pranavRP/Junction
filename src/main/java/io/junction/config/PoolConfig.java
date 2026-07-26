package io.junction.config;

import java.util.List;

/**
 * A named group of interchangeable backends.
 *
 * <p>Phase 1 carries only identity and membership. Health, breaker, retry, and
 * outlier settings (architecture.md §7) arrive with the phases that implement
 * them, so an unimplemented knob is never present in config pretending to work.
 */
public record PoolConfig(String name, List<BackendConfig> backends) {

    public PoolConfig {
        backends = List.copyOf(backends);
    }
}
