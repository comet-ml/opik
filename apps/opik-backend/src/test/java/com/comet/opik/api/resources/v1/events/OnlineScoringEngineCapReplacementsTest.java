package com.comet.opik.api.resources.v1.events;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineScoringEngineCapReplacementsTest {

    private static final String HINT = "use read(type=trace, id=abc, tier=FULL) to see full";

    @Test
    void leavesShortValuesUntouched() {
        var input = Map.of("a", "tiny", "b", "also small");

        var out = OnlineScoringEngine.capReplacements(input, 100, HINT, Set.of());

        assertThat(out.get("a")).isEqualTo("tiny");
        assertThat(out.get("b")).isEqualTo("also small");
    }

    @Test
    void truncatesValuesOverCapWithHintAndDroppedCount() {
        var big = "x".repeat(5_000);
        var input = Map.of("input", big);

        var out = OnlineScoringEngine.capReplacements(input, 4_000, HINT, Set.of());

        var truncated = out.get("input");
        assertThat(truncated).startsWith("x".repeat(4_000));
        assertThat(truncated).endsWith(
                "[TRUNCATED 1,000 chars — use read(type=trace, id=abc, tier=FULL) to see full]");
    }

    @Test
    void capAtBoundaryDoesNotTruncate() {
        var atCap = "y".repeat(4_000);
        var input = Map.of("input", atCap);

        var out = OnlineScoringEngine.capReplacements(input, 4_000, HINT, Set.of());

        // length == cap → no truncation suffix
        assertThat(out.get("input")).isEqualTo(atCap);
    }

    @Test
    void preservesIterationOrder() {
        var input = new LinkedHashMap<String, String>();
        input.put("first", "f");
        input.put("second", "s");
        input.put("third", "t");

        var out = OnlineScoringEngine.capReplacements(input, 100, HINT, Set.of());

        assertThat(out.keySet()).containsExactly("first", "second", "third");
    }

    @Nested
    class UserMappedVariableKeysTests {

        @Test
        void traceSectionVariablesAreUserMapped() {
            var variables = Map.of(
                    "output", "output",
                    "expected", "metadata.expected_output",
                    "question", "input.question");

            var keys = OnlineScoringEngine.userMappedVariableKeys(variables);

            assertThat(keys).containsExactlyInAnyOrder("output", "expected", "question");
        }

        @Test
        void allSentinelVariablesAreExcluded() {
            var variables = Map.of(
                    "output", "output",
                    "my_spans", "spans",
                    "my_trace", "trace",
                    "my_span", "span");

            var keys = OnlineScoringEngine.userMappedVariableKeys(variables);

            assertThat(keys).containsExactlyInAnyOrder("output");
        }

        @Test
        void variableKeyNamedAfterSentinelButMappedToTraceSectionIsUserMapped() {
            var variables = Map.of("spans", "output");

            var keys = OnlineScoringEngine.userMappedVariableKeys(variables);

            assertThat(keys).containsExactlyInAnyOrder("spans");
        }

    }

    @Nested
    class SelectiveCappingTests {

        @Test
        void userMappedVariablesAreNotCapped() {
            var bigOutput = "x".repeat(10_000);
            var replacements = new LinkedHashMap<String, String>();
            replacements.put("output", bigOutput);
            replacements.put("expected", "short expected");

            var userKeys = Set.of("output", "expected");
            var out = OnlineScoringEngine.capReplacements(
                    replacements, 4_000, HINT, userKeys);

            assertThat(out.get("output")).isEqualTo(bigOutput);
            assertThat(out.get("expected")).isEqualTo("short expected");
        }

        @Test
        void sentinelVariablesAreStillCapped() {
            var bigSpans = "s".repeat(10_000);
            var replacements = new LinkedHashMap<String, String>();
            replacements.put("output", "x".repeat(10_000));
            replacements.put("spans", bigSpans);

            var userKeys = Set.of("output");
            var out = OnlineScoringEngine.capReplacements(
                    replacements, 4_000, HINT, userKeys);

            assertThat(out.get("output")).hasSize(10_000);
            assertThat(out.get("spans")).startsWith("s".repeat(4_000));
            assertThat(out.get("spans")).contains("[TRUNCATED");
        }

        @Test
        void emptyUserKeysCapEverything() {
            var replacements = new LinkedHashMap<String, String>();
            replacements.put("spans", "s".repeat(10_000));
            replacements.put("trace", "t".repeat(10_000));

            var out = OnlineScoringEngine.capReplacements(
                    replacements, 4_000, HINT, Set.of());

            assertThat(out.get("spans")).startsWith("s".repeat(4_000));
            assertThat(out.get("spans")).contains("[TRUNCATED");
            assertThat(out.get("trace")).startsWith("t".repeat(4_000));
            assertThat(out.get("trace")).contains("[TRUNCATED");
        }

    }
}
