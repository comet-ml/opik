package com.comet.opik.api.resources.v1.events;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineScoringEngineCapReplacementsTest {

    private static final String HINT = "use read(type=trace, id=abc, tier=FULL) to see full";

    @Test
    void leavesShortValuesUntouched() {
        var input = Map.of("a", "tiny", "b", "also small");

        var out = OnlineScoringEngine.capReplacements(input, 100, HINT);

        assertThat(out.get("a")).isEqualTo("tiny");
        assertThat(out.get("b")).isEqualTo("also small");
    }

    @Test
    void truncatesValuesOverCapWithHintAndDroppedCount() {
        var big = "x".repeat(5_000);
        var input = Map.of("input", big);

        var out = OnlineScoringEngine.capReplacements(input, 4_000, HINT);

        var truncated = out.get("input");
        assertThat(truncated).startsWith("x".repeat(4_000));
        assertThat(truncated).endsWith(
                "[TRUNCATED 1,000 chars — use read(type=trace, id=abc, tier=FULL) to see full]");
    }

    @Test
    void capAtBoundaryDoesNotTruncate() {
        var atCap = "y".repeat(4_000);
        var input = Map.of("input", atCap);

        var out = OnlineScoringEngine.capReplacements(input, 4_000, HINT);

        // length == cap → no truncation suffix
        assertThat(out.get("input")).isEqualTo(atCap);
    }

    @Test
    void preservesIterationOrder() {
        var input = new LinkedHashMap<String, String>();
        input.put("first", "f");
        input.put("second", "s");
        input.put("third", "t");

        var out = OnlineScoringEngine.capReplacements(input, 100, HINT);

        assertThat(out.keySet()).containsExactly("first", "second", "third");
    }
}