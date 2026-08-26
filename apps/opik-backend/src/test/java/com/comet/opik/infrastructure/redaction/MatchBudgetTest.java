package com.comet.opik.infrastructure.redaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Match Budget")
class MatchBudgetTest {

    /**
     * The shape the configuration warns about: an unanchored leading quantifier over a class that can consume a
     * whole token, so the matcher backtracks across every split of a run it can never complete a match on.
     */
    private static final String UNANCHORED_EMAIL = "[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}";

    /** Same intent, anchored - what an operator should write, and what the runaway case must not require. */
    private static final String ANCHORED_EMAIL = "(?<![\\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}";

    private static RedactionRules rules(String regex) {
        return new RedactionRules(List.of(RedactionRule.of(regex, "[EMAIL]")));
    }

    /** A run of characters the pattern can consume but never complete a match on, and with no '/' to break it. */
    private static String unbrokenRun(int length) {
        return "aB3_x-Yz9Q".repeat(length / 10);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    @DisplayName("a runaway rule is stopped instead of running for minutes on one read")
    void aRunawayRuleIsStopped() {
        // Unbudgeted this is quadratic: ~1.8s at 32 KB, and jacksonConfig.maxStringLength permits 100 MB. The
        // timeout is the assertion that matters - without the budget this method does not return.
        var masked = rules(UNANCHORED_EMAIL).apply(unbrokenRun(1_000_000));

        assertThat(masked).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("aborting masks the value whole rather than returning it as stored")
    void abortingMasksTheValueWhole() {
        var stored = unbrokenRun(200_000);

        // Fail closed: the value is the caller's own content, so whoever chooses it must not choose the outcome.
        assertThat(rules(UNANCHORED_EMAIL).apply(stored)).doesNotContain(stored);
    }

    @Test
    @DisplayName("an anchored rule handles the same input without coming near the budget")
    void anAnchoredRuleHandlesTheSameInput() {
        var stored = unbrokenRun(200_000);

        // Not merely faster: it does not abort, so the operator who anchors keeps exact masking on large values.
        assertThat(rules(ANCHORED_EMAIL).apply(stored)).isEqualTo(stored);
    }

    @Test
    @DisplayName("ordinary values are rewritten exactly as before the budget existed")
    void ordinaryValuesAreRewrittenExactly() {
        var stored = "Refund for john.doe@example.com, cc jane@corp.co.uk";

        assertThat(rules(ANCHORED_EMAIL).apply(stored)).isEqualTo("Refund for [EMAIL], cc [EMAIL]");
    }

    @Test
    @DisplayName("a value with no match is returned unchanged and identical")
    void aValueWithNoMatchIsReturnedUnchanged() {
        var stored = "nothing sensitive in this sentence at all";

        assertThat(rules(ANCHORED_EMAIL).apply(stored)).isSameAs(stored);
    }

    @Test
    @DisplayName("rules still see the previous rule's output, so ordering behaves as documented")
    void rulesStillSeeThePreviousRulesOutput() {
        var chained = new RedactionRules(List.of(
                RedactionRule.of("secret", "hidden"),
                RedactionRule.of("hidden", "[GONE]")));

        assertThat(chained.apply("a secret value")).isEqualTo("a [GONE] value");
    }
}
