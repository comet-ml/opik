package com.comet.opik.infrastructure.redaction;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * An ordered rule set. Rules are applied in sequence and each sees the previous rule's output, which is how
 * the SDK behaves — a broad pattern placed first will consume the matches a narrower one was written for.
 */
@Slf4j
public record RedactionRules(@NonNull List<RedactionRule> rules) {

    /**
     * Written in place of a value whose rules could not be evaluated within budget. Not configurable: it is a
     * failure signal, and an operator tuning it would be tuning how a failure looks rather than fixing it.
     */
    private static final String UNEVALUATED = "[REDACTED]";

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
            String rewritten = rule.apply(result, MatchBudget.DEFAULT);

            if (rewritten == MatchBudget.EXCEEDED) {
                // Fail closed. A rule that could not be evaluated is not evidence the value is safe to show, and
                // the value is the caller's own content, so an attacker choosing it must not choose the outcome.
                log.warn("Redaction budget exhausted on a value of '{}' characters; masking it whole",
                        text.length());
                return UNEVALUATED;
            }

            result = rewritten;
        }

        return result;
    }
}
