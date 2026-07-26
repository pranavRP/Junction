package io.junction.config;

import java.util.List;

/**
 * Outcome of loading config. Sealed rather than exception-or-null (R-13) so the
 * caller is forced to handle the invalid case explicitly.
 *
 * <p>{@link Invalid} carries <em>every</em> error, not just the first. Fixing a
 * config file one error per restart is a miserable operator experience, and the
 * operator is the primary persona (pr.md U1).
 */
public sealed interface ConfigResult {

    record Valid(JunctionConfig config) implements ConfigResult {}

    record Invalid(List<String> errors) implements ConfigResult {
        public Invalid {
            errors = List.copyOf(errors);
        }

        public String message() {
            return "Invalid configuration (" + errors.size() + " error"
                    + (errors.size() == 1 ? "" : "s") + "):\n  - "
                    + String.join("\n  - ", errors);
        }
    }
}
