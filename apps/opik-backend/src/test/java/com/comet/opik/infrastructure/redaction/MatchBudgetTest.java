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

    @Test
    @DisplayName("a large value with no match survives, because the budget scales with its length")
    void aLargeValueWithNoMatchSurvives() {
        // An absolute ceiling failed closed on size alone: a linear scan of a 5 MB value costs more accesses
        // than a fixed 2M budget allows, so legitimate content containing no match at all was destroyed.
        var stored = "the assistant answered the question without any identifiers present. ".repeat(75_000);

        assertThat(stored.length()).isGreaterThan(5_000_000);
        assertThat(rules(ANCHORED_EMAIL).apply(stored)).isSameAs(stored);
    }

    @Test
    @DisplayName("the budget is bounded above as well as below, so the largest value cannot buy 20s of CPU")
    void theBudgetIsBoundedAboveAsWellAsBelow() {
        // 100 accesses per character is unbounded in the one variable a caller controls: at the 100 MB
        // jacksonConfig.maxStringLength permits, it is 10.5 billion accesses, ~20s on a request thread.
        int maxStringLength = 104_857_600;

        assertThat(MatchBudget.limitFor(maxStringLength)).isEqualTo(1_000_000_000L);
        // Still generous where it binds: ~9.5 accesses per character at that size, against the 1.0-3.2 a
        // legitimate anchored rule was measured to use.
        assertThat((double) MatchBudget.limitFor(maxStringLength) / maxStringLength).isGreaterThan(9.0);
    }

    @Test
    @DisplayName("the ceiling does not bind on the values the scaled allowance was written for")
    void theCeilingDoesNotBindOnOrdinaryValues() {
        assertThat(MatchBudget.limitFor(0)).isEqualTo(100_000L);
        assertThat(MatchBudget.limitFor(1_000)).isEqualTo(100_000L);
        assertThat(MatchBudget.limitFor(5_000_000)).isEqualTo(500_000_000L);
    }

    @Test
    @DisplayName("a replacement longer than what it replaces cannot multiply the response")
    void aLongReplacementCannotMultiplyTheResponse() {
        // Unbounded this returns a string ten times the length of its input, and the input is a caller's own
        // content up to jacksonConfig.maxStringLength - 100 MB in, a gigabyte out.
        var amplifying = new RedactionRules(List.of(RedactionRule.of("[0-9]", "[REDACTED]")));
        var digits = "0123456789".repeat(20_000);

        var masked = amplifying.apply(digits);

        assertThat(masked).isEqualTo("[REDACTED]");
        assertThat(masked.length()).isLessThan(digits.length());
    }

    @Test
    @DisplayName("chained rules cannot compound the growth between them")
    void chainedRulesCannotCompoundTheGrowth() {
        // Ten times, then ten times again: bounding each rule against its own input would allow the product.
        var chained = new RedactionRules(List.of(
                RedactionRule.of("[0-9]", "0000000000"),
                RedactionRule.of("0", "1111111111")));
        var digits = "0123456789".repeat(20_000);

        assertThat(chained.apply(digits)).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("a token longer than the value it masks is still emitted")
    void aTokenLongerThanTheValueItMasksIsStillEmitted() {
        // The ordinary shape of an expanding rule, and the reason for the floor: growth measured as a factor
        // alone would refuse to replace a short match with a long token.
        var expanding = new RedactionRules(List.of(
                RedactionRule.of("\\b\\d{3}-\\d{3}-\\d{4}\\b", "[PHONE_NUMBER_REDACTED_FOR_THIS_READER]")));

        assertThat(expanding.apply("555-123-4567"))
                .isEqualTo("[PHONE_NUMBER_REDACTED_FOR_THIS_READER]");
    }

    @Test
    @DisplayName("a large value whose rewrite stays within the bound is rewritten, not masked")
    void aLargeValueWithinTheBoundIsRewritten() {
        var expanding = new RedactionRules(List.of(RedactionRule.of("secret", "[REDACTED]")));
        var stored = "the secret is in here somewhere among other words ".repeat(20_000);

        var rewritten = expanding.apply(stored);

        assertThat(rewritten).doesNotContain("secret").contains("[REDACTED]");
        assertThat(rewritten.length()).isGreaterThan(stored.length());
    }

    @Test
    @DisplayName("a replacement is emitted literally, not with its own escaping visible")
    void aReplacementIsEmittedLiterally() {
        // Matcher.quoteReplacement escapes $ and \ for appendReplacement to undo. Appending the quoted form
        // directly - as the budgeted loop does - would emit the escapes themselves.
        var backreferenceShaped = new RedactionRules(List.of(RedactionRule.of("secret", "\\1***@\\2")));

        assertThat(backreferenceShaped.apply("a secret value")).isEqualTo("a \\1***@\\2 value");
    }

    @Test
    @DisplayName("a replacement containing a dollar sign is emitted as written")
    void aReplacementContainingADollarSignIsEmittedAsWritten() {
        var dollars = new RedactionRules(List.of(RedactionRule.of("amount", "$$$")));

        assertThat(dollars.apply("the amount here")).isEqualTo("the $$$ here");
    }
}
