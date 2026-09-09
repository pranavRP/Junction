package io.junction.balance;

import io.junction.backend.BackendRuntime;

/**
 * Outcome of backend selection (R-13).
 *
 * <p>Sealed rather than a nullable return, because "no backend available" is a
 * client-visible 503 that needs a reason label for metrics (R-33), not a
 * NullPointerException three frames later.
 *
 * <p><b>Panic mode is deliberately not a variant here.</b> Phase 3 planned one,
 * then found the better shape: panic changes which backends are <em>selectable</em>,
 * not what a pick <em>returns</em>. Expressing it as a flag the pool sets before
 * delegating means all four strategies work unchanged in panic and keep their own
 * behaviour while in it. A PanicMode result would have forced every caller to
 * handle a third case that behaves exactly like {@link Chosen}.
 */
public sealed interface PickResult {

    record Chosen(BackendRuntime backend) implements PickResult {}

    /** @param reason closed-enum token for the metrics reason label */
    record NoneAvailable(String reason) implements PickResult {}
}
