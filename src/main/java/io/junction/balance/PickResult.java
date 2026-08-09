package io.junction.balance;

import io.junction.backend.BackendRuntime;

/**
 * Outcome of backend selection (R-13).
 *
 * <p>Sealed rather than a nullable return, because "no backend available" is a
 * client-visible 503 that needs a reason label for metrics (R-33), not a
 * NullPointerException three frames later.
 *
 * <p>{@code PanicMode} — routing to unhealthy backends when the whole pool is
 * down (FR-3.6) — is a Phase 3 variant and is absent until it works.
 */
public sealed interface PickResult {

    record Chosen(BackendRuntime backend) implements PickResult {}

    /** @param reason closed-enum token for the metrics reason label */
    record NoneAvailable(String reason) implements PickResult {}
}
