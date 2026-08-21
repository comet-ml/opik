package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Variable extraction out of a trace's input/output/metadata section. Pure static functions, so no
 * containers here — see {@link OnlineScoringEngineTest} for the end-to-end scoring flow.
 * <p>
 * The scalar-section cases are the regression: a trace whose section is a bare JSON string (or array)
 * used to blow up {@code toReplacements} with a {@code MismatchedInputException}, which propagated out
 * of {@code prepareLlmRequest} and failed the whole evaluation before the LLM was ever called.
 */
@DisplayName("OnlineScoringEngine variable extraction")
class OnlineScoringEngineExtractFromJsonTest {

    private static Trace traceWithOutput(String outputJson) {
        return Trace.builder()
                .id(UUID.randomUUID())
                .projectName("project")
                .projectId(UUID.randomUUID())
                .createdBy("user")
                .output(JsonUtils.getJsonNodeFromString(outputJson))
                .build();
    }

    @Test
    @DisplayName("nested path against a bare-string section drops the variable instead of throwing")
    void nestedPathOnStringSection() {
        var trace = traceWithOutput("\"Motor, elektrik, vites veya gövde?\"");
        var variables = Map.of("answer", "output.answer");

        assertThatCode(() -> OnlineScoringEngine.toReplacements(variables, trace)).doesNotThrowAnyException();
        assertThat(OnlineScoringEngine.toReplacements(variables, trace)).doesNotContainKey("answer");
    }

    @Test
    @DisplayName("whole-section mapping of a bare-string section still resolves")
    void wholeSectionOnStringSection() {
        var trace = traceWithOutput("\"just a string\"");

        var replacements = OnlineScoringEngine.toReplacements(Map.of("output", "output"), trace);

        assertThat(replacements).containsEntry("output", "\"just a string\"");
    }

    @Test
    @DisplayName("nested path against a numeric section drops the variable instead of throwing")
    void nestedPathOnNumericSection() {
        var trace = traceWithOutput("42");

        var replacements = OnlineScoringEngine.toReplacements(Map.of("score", "output.score"), trace);

        assertThat(replacements).doesNotContainKey("score");
    }

    @Test
    @DisplayName("indexed path against an array section resolves the element")
    void indexedPathOnArraySection() {
        var trace = traceWithOutput("[{\"name\": \"first\"}, {\"name\": \"second\"}]");

        var replacements = OnlineScoringEngine.toReplacements(Map.of("name", "output.[0].name"), trace);

        assertThat(replacements).containsEntry("name", "first");
    }

    @Test
    @DisplayName("field path against an array section drops the variable instead of throwing")
    void fieldPathOnArraySection() {
        var trace = traceWithOutput("[\"a\", \"b\"]");

        var replacements = OnlineScoringEngine.toReplacements(Map.of("name", "output.name"), trace);

        assertThat(replacements).doesNotContainKey("name");
    }

    @Test
    @DisplayName("nested and flat paths against an object section keep working")
    void objectSectionStillResolves() {
        var trace = traceWithOutput("""
                {
                    "answer": "42",
                    "details": {"unit": "meters"},
                    "flat.key": "flat value"
                }
                """);

        var replacements = OnlineScoringEngine.toReplacements(
                Map.of(
                        "answer", "output.answer",
                        "unit", "output.details.unit",
                        "flat", "output.flat.key"),
                trace);

        assertThat(replacements).containsEntry("answer", "42");
        assertThat(replacements).containsEntry("unit", "meters");
        // JsonPath reads "$.flat.key" as a nested miss, then the flat-structure fallback finds the
        // literal "flat.key" property.
        assertThat(replacements).containsEntry("flat", "flat value");
    }
}
