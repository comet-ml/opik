package com.comet.opik.infrastructure.redaction;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

/**
 * The rule set in force while the current response is being written.
 * <p>
 * Held per thread because redaction happens during serialization, after the resource method has returned:
 * there is no argument left to pass it through. Empty means "write the values as stored", which is what
 * every request gets when the feature is off or the caller may see originals.
 */
@UtilityClass
public class RedactionContext {

    private static final ThreadLocal<RedactionRules> CURRENT = new ThreadLocal<>();

    public void set(@NonNull RedactionRules rules) {
        CURRENT.set(rules);
    }

    public void clear() {
        CURRENT.remove();
    }

    public RedactionRules current() {
        RedactionRules rules = CURRENT.get();
        return rules == null ? RedactionRules.empty() : rules;
    }
}
