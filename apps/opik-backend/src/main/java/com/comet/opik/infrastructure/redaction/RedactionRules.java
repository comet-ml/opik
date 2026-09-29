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

    public RedactionRules(@NonNull List<RedactionRule> rules) {
        // Copied, not held. This is the policy in force for the life of the process - RedactionService compiles
        // it once at startup and every response reads it - so it must not be something a caller can still
        // change afterwards.
        this.rules = List.copyOf(rules);
    }

    /**
     * Written in place of a value whose rules could not be evaluated within budget. Not configurable: it is a
     * failure signal, and an operator tuning it would be tuning how a failure looks rather than fixing it.
     */
    private static final String UNEVALUATED = "[REDACTED]";

    private static final RedactionRules EMPTY = new RedactionRules(List.of());

    /**
     * How much longer than the value it was given a rewrite may come out. Four, because a replacement is
     * ordinarily shorter than the text it replaces - a rule masking a match to a token is what the
     * configuration describes - and the cases where it is longer replace short matches with a longer token,
     * which is nowhere near quadrupling the whole value.
     */
    private static final long MAXIMUM_GROWTH_FACTOR = 4L;

    /** Floor, so a short value can be replaced by a much longer token without hitting the bound. */
    private static final long MINIMUM_OUTPUT = 64 * 1024L;

    public static RedactionRules empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public String apply(@NonNull String text) {
        String result = text;
        // Measured against the value as stored, not against each rule's input, so a chain cannot compound: two
        // rules that each quadruple would otherwise be allowed sixteen times the original between them.
        long outputLimit = Math.max(MINIMUM_OUTPUT, MAXIMUM_GROWTH_FACTOR * text.length());

        for (RedactionRule rule : rules) {
            String rewritten = rule.apply(result, MatchBudget.forValue(result), outputLimit);

            if (rewritten == MatchBudget.EXCEEDED) {
                // Fail closed. A rule that could not be evaluated is not evidence the value is safe to show, and
                // the value is the caller's own content, so an attacker choosing it must not choose the outcome.
                // debug, not warn: this is per value, so a wide page would flood the log at warn.
                log.debug("Redaction bounds exhausted on a value of '{}' characters; masking it whole",
                        result.length());
                return UNEVALUATED;
            }

            result = rewritten;
        }

        return result;
    }
}
