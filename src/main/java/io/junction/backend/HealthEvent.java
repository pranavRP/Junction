package io.junction.backend;

/**
 * Everything that can move a backend between health states.
 *
 * <p>Modelled as data rather than as methods on the tracker so the full
 * transition space is a finite {@code states × events} grid that a table-driven
 * test can enumerate exhaustively, illegal combinations included (R-21).
 */
public sealed interface HealthEvent {

    /** An active probe returned 2xx within its timeout. */
    record ProbeSucceeded() implements HealthEvent {}

    /** An active probe failed, timed out, or returned a non-2xx. */
    record ProbeFailed(String reason) implements HealthEvent {}

    /** Weight dropped to 0, or an operator asked for a drain. */
    record DrainRequested() implements HealthEvent {}

    /** In-flight count reached zero — only meaningful while draining. */
    record InflightDrained() implements HealthEvent {}
}
