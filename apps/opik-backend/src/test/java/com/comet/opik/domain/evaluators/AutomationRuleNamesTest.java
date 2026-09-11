package com.comet.opik.domain.evaluators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("AutomationRuleNames")
class AutomationRuleNamesTest {

    /**
     * Cases whose whole contract is "given these existing names, the requested one resolves to that".
     * Truncation is covered by standalone tests below instead, because those assert on length and code
     * points rather than on an expected string.
     */
    static Stream<Arguments> uniqueNameCases() {
        return Stream.of(
                arguments("no collision at all", "Hallucination", Set.of(), "Hallucination"),
                arguments("no collision among unrelated names", "Hallucination", Set.of("Relevance", "Toxicity"),
                        "Hallucination"),
                arguments("first collision appends -1", "Hallucination", Set.of("Hallucination"), "Hallucination-1"),
                arguments("second collision increments", "Hallucination", Set.of("Hallucination", "Hallucination-1"),
                        "Hallucination-2"),
                arguments("third collision increments", "Hallucination",
                        Set.of("Hallucination", "Hallucination-1", "Hallucination-2"), "Hallucination-3"),
                arguments("fills the smallest free suffix", "Hallucination", Set.of("Hallucination", "Hallucination-2"),
                        "Hallucination-1"),
                arguments("reuses a base name that was freed", "Hallucination",
                        Set.of("Hallucination-1", "Hallucination-2"), "Hallucination"),
                arguments("ignores names that merely share the prefix", "Hallucination",
                        Set.of("Hallucination Rate", "Hallucination-abc", "Hallucinations"), "Hallucination"),
                arguments("suffixes past prefix lookalikes", "Hallucination",
                        Set.of("Hallucination", "Hallucination Rate", "Hallucination-abc"), "Hallucination-1"),
                arguments("regex-special characters are treated literally", "GPT-4 (v2)", Set.of("GPT-4 (v2)"),
                        "GPT-4 (v2)-1"),
                // Case, accent and trailing-space folding all approximate the utf8mb4_unicode_ci collation.
                arguments("case-insensitive collision", "hallucination", Set.of("Hallucination"), "hallucination-1"),
                arguments("case-insensitive collision keeps counting", "HALLUCINATION",
                        Set.of("Hallucination", "hallucination-1"), "HALLUCINATION-2"),
                arguments("accent-insensitive collision", "Cafe", Set.of("Café"), "Cafe-1"),
                arguments("trailing space on the requested name", "Quality ", Set.of("Quality"), "Quality -1"),
                arguments("trailing space on the existing name", "Quality", Set.of("Quality "), "Quality-1"),
                // Blank names are returned untouched rather than suffixed; @NotBlank rejects them upstream.
                arguments("null name is returned unchanged", null, Set.of("Hallucination"), null),
                arguments("empty name is returned unchanged", "", Set.of("Hallucination"), ""),
                arguments("whitespace-only name is returned unchanged", "   ", Set.of("Hallucination"), "   "));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("uniqueNameCases")
    @DisplayName("generateUniqueName resolves a free name")
    void generateUniqueName(String description, String requestedName, Set<String> existingNames, String expected) {
        assertThat(AutomationRuleNames.generateUniqueName(requestedName, existingNames)).isEqualTo(expected);
    }

    /**
     * {@code likePrefix} builds the SQL {@code LIKE} search pattern used to find collision candidates. Its
     * return value is <em>never</em> stored and never shown to anyone: a rule the user names {@code 50%} is
     * still persisted and displayed as {@code 50%}. Only the suffix in
     * {@link #generateUniqueName} changes a stored name. The end-to-end proof that escaping does not leak
     * into stored names is {@code AutomationRuleEvaluatorsResourceTest.DuplicateNameHandling
     * #whenNameHasLikeMetacharacters__thenSuffixIsAppended}, which asserts the name comes back verbatim.
     */
    @Test
    @DisplayName("likePrefix builds a search pattern (escaping metacharacters); it never alters the stored name")
    void likePrefixEscaping() {
        assertThat(AutomationRuleNames.likePrefix("Hallucination")).isEqualTo("Hallucination");
        assertThat(AutomationRuleNames.likePrefix("Quality ")).isEqualTo("Quality");
        assertThat(AutomationRuleNames.likePrefix("a_b")).isEqualTo("a!_b");
        assertThat(AutomationRuleNames.likePrefix("50%")).isEqualTo("50!%");
        // The escape character itself is escaped first, so we do not double-escape the escapes we add.
        assertThat(AutomationRuleNames.likePrefix("wow!")).isEqualTo("wow!!");
        assertThat(AutomationRuleNames.likePrefix("50%!")).isEqualTo("50!%!!");
        // A backslash is not the escape character and is bound, not interpolated, so it passes through.
        assertThat(AutomationRuleNames.likePrefix("a\\b")).isEqualTo("a\\b");
        assertThat(AutomationRuleNames.likePrefix(null)).isNull();
    }

    @Test
    @DisplayName("likePrefix caps long names so truncated suffix candidates still share the prefix")
    void likePrefixTruncatesLongNames() {
        // Suffixing a 150-char name stores a truncated base ("a"x148 + "-1"), which no longer starts with
        // the full name - the search prefix must be capped (150 minus the 12-char worst-case reserve:
        // "-2147483647" plus truncateToFit's surrogate back-off) to still match it.
        assertThat(AutomationRuleNames.likePrefix("a".repeat(150))).isEqualTo("a".repeat(138));
        // A name at the cap is left untouched.
        assertThat(AutomationRuleNames.likePrefix("a".repeat(138))).isEqualTo("a".repeat(138));
        // The cap must not split a surrogate pair: with 137 chars + emojis the cut at 138 falls inside the
        // first pair, so it backs off by one.
        String emojiName = "a".repeat(137) + "😀".repeat(7);
        assertThat(AutomationRuleNames.likePrefix(emojiName)).isEqualTo("a".repeat(137));
    }

    @Test
    @DisplayName("truncates to fit the 150-char column when suffixing")
    void truncatesLongNames() {
        String base = "a".repeat(150);
        String result = AutomationRuleNames.generateUniqueName(base, Set.of(base));
        assertThat(result).hasSize(150).endsWith("-1");
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
