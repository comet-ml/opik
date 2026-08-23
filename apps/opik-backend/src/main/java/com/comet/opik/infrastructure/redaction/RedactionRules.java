package com.comet.opik.infrastructure.redaction;

import lombok.NonNull;

import java.util.List;

/**
 * An ordered rule set. Rules are applied in sequence and each sees the previous rule's output, which is how
 * the SDK behaves — a broad pattern placed first will consume the matches a narrower one was written for.
 */
public record RedactionRules(@NonNull List<RedactionRule> rules) {

    private static final RedactionRules EMPTY = new RedactionRules(List.of());

    public static RedactionRules empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public String apply(@NonNull String text) {
        String result = text;

        for (RedactionRule rule : rules) {
            result = rule.apply(result);
        }

        return result;
    }
}
