package com.comet.opik.domain.evaluators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AutomationRuleNames")
class AutomationRuleNamesTest {

    @Test
    @DisplayName("returns the requested name when nothing collides")
    void noCollision() {
        assertThat(AutomationRuleNames.generateUniqueName("Hallucination", Set.of()))
                .isEqualTo("Hallucination");
        assertThat(AutomationRuleNames.generateUniqueName("Hallucination", Set.of("Relevance", "Toxicity")))
                .isEqualTo("Hallucination");
    }

    @Test
    @DisplayName("appends -1 on the first collision and increments after that")
    void incrementsSuffix() {
        assertThat(AutomationRuleNames.generateUniqueName("Hallucination", Set.of("Hallucination")))
                .isEqualTo("Hallucination-1");
        assertThat(AutomationRuleNames.generateUniqueName("Hallucination",
                Set.of("Hallucination", "Hallucination-1")))
                .isEqualTo("Hallucination-2");
        assertThat(AutomationRuleNames.generateUniqueName("Hallucination",
                Set.of("Hallucination", "Hallucination-1", "Hallucination-2")))
                .isEqualTo("Hallucination-3");
    }

    @Test
    @DisplayName("fills the smallest free suffix when there are gaps")
    void fillsGaps() {
        // base and -2 are taken, -1 is free -> reuse -1
        assertThat(AutomationRuleNames.generateUniqueName("Hallucination",
                Set.of("Hallucination", "Hallucination-2")))
                .isEqualTo("Hallucination-1");
    }

    @Test
    @DisplayName("reuses the base name when it was freed but suffixed names remain")
    void reusesFreedBase() {
        assertThat(AutomationRuleNames.generateUniqueName("Hallucination",
                Set.of("Hallucination-1", "Hallucination-2")))
                .isEqualTo("Hallucination");
    }

    @Test
    @DisplayName("ignores unrelated names that merely share the prefix")
    void ignoresPrefixOnlyMatches() {
        assertThat(AutomationRuleNames.generateUniqueName("Hallucination",
                Set.of("Hallucination Rate", "Hallucination-abc", "Hallucinations")))
                .isEqualTo("Hallucination");
        assertThat(AutomationRuleNames.generateUniqueName("Hallucination",
                Set.of("Hallucination", "Hallucination Rate", "Hallucination-abc")))
                .isEqualTo("Hallucination-1");
    }

    @Test
    @DisplayName("treats names with regex-special characters literally")
    void handlesRegexSpecialChars() {
        assertThat(AutomationRuleNames.generateUniqueName("GPT-4 (v2)", Set.of("GPT-4 (v2)")))
                .isEqualTo("GPT-4 (v2)-1");
    }

    @Test
    @DisplayName("treats names differing only by case as collisions (matches DB collation)")
    void caseInsensitiveCollision() {
        assertThat(AutomationRuleNames.generateUniqueName("hallucination", Set.of("Hallucination")))
                .isEqualTo("hallucination-1");
        assertThat(AutomationRuleNames.generateUniqueName("HALLUCINATION",
                Set.of("Hallucination", "hallucination-1")))
                .isEqualTo("HALLUCINATION-2");
    }

    @Test
    @DisplayName("does not regenerate an existing suffixed name for near-max-length names on re-run")
    void longNameDoesNotCollideOnRerun() {
        String base = "a".repeat(150);
        String first = AutomationRuleNames.generateUniqueName(base, Set.of(base));
        assertThat(first).hasSize(150).endsWith("-1");
        // Re-run: both the base and the previously generated name exist -> must produce a distinct name.
        String second = AutomationRuleNames.generateUniqueName(base, Set.of(base, first));
        assertThat(second).isNotEqualTo(first).endsWith("-2");
    }

    @Test
    @DisplayName("returns null and empty names unchanged")
    void nullOrEmptyNames() {
        assertThat(AutomationRuleNames.generateUniqueName(null, Set.of("Hallucination"))).isNull();
        assertThat(AutomationRuleNames.generateUniqueName("", Set.of("Hallucination"))).isEmpty();
    }

    @Test
    @DisplayName("returns whitespace-only names unchanged")
    void whitespaceOnlyName() {
        assertThat(AutomationRuleNames.generateUniqueName("   ", Set.of("Hallucination"))).isEqualTo("   ");
    }

    @Test
    @DisplayName("treats accented names as collisions (matches unicode_ci collation)")
    void accentInsensitiveCollision() {
        assertThat(AutomationRuleNames.generateUniqueName("Cafe", Set.of("Café")))
                .isEqualTo("Cafe-1");
    }

    @Test
    @DisplayName("truncates to fit the 150-char column when suffixing")
    void truncatesLongNames() {
        String base = "a".repeat(150);
        String result = AutomationRuleNames.generateUniqueName(base, Set.of(base));
        assertThat(result).hasSize(150).endsWith("-1");
    }

    @Test
    @DisplayName("does not split a surrogate pair when truncating a name ending in a non-BMP character")
    void truncatesOnCodePointBoundary() {
        // 147 ASCII chars + one emoji (2 UTF-16 units) = 149 units. With a "-1" suffix the cut (index 148)
        // falls between the emoji's surrogate pair, so truncation must not leave a lone surrogate.
        String base = "a".repeat(147) + "😀";
        String result = AutomationRuleNames.generateUniqueName(base, Set.of(base));
        assertThat(result).endsWith("-1");
        assertThat(result.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)).isFalse();
    }
}
