package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.SpanType;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Variable extraction out of a trace's or span's input/output/metadata section. Pure static functions,
 * so no containers here — see {@link OnlineScoringEngineTest} for the end-to-end scoring flow.
 * <p>
 * The non-object-section cases are the regression: a section that is a bare JSON string (or array) used
 * to blow up {@code toReplacements} with a {@code MismatchedInputException}, which propagated out of
 * {@code prepareLlmRequest} and failed the whole evaluation before the LLM was ever called.
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

    private static Span spanWithOutput(String outputJson) {
        return Span.builder()
                .id(UUID.randomUUID())
                .name("span")
                .type(SpanType.llm)
                .startTime(Instant.now())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .output(JsonUtils.getJsonNodeFromString(outputJson))
                .build();
    }

    /**
     * Section shapes that carry no nested path: the mapping cannot resolve, and the contract is that the
     * variable is dropped rather than the evaluation failing.
     */
    static Stream<Arguments> unresolvableSections() {
        return Stream.of(
                arguments("bare string", "\"Motor, elektrik, vites veya gövde?\"", "output.answer"),
                arguments("number", "42", "output.score"),
                arguments("boolean", "true", "output.flag"),
                arguments("null", "null", "output.answer"),
                arguments("array, field path", "[\"a\", \"b\"]", "output.name"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unresolvableSections")
    @DisplayName("an unresolvable path drops the variable instead of throwing")
    void unresolvablePathDropsTheVariable(String shape, String outputJson, String mapping) {
        var trace = traceWithOutput(outputJson);
        var variables = Map.of("variable", mapping);

        assertThatCode(() -> OnlineScoringEngine.toReplacements(variables, trace)).doesNotThrowAnyException();
        assertThat(OnlineScoringEngine.toReplacements(variables, trace)).doesNotContainKey("variable");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unresolvableSections")
    @DisplayName("the span overload behaves the same for an unresolvable path")
    void unresolvablePathOnSpanDropsTheVariable(String shape, String outputJson, String mapping) {
        var span = spanWithOutput(outputJson);
        var variables = Map.of("variable", mapping);

        assertThatCode(() -> OnlineScoringEngine.toReplacements(variables, span)).doesNotThrowAnyException();
        assertThat(OnlineScoringEngine.toReplacements(variables, span)).doesNotContainKey("variable");
    }

    @Test
    @DisplayName("whole-section mapping of a bare-string section still resolves")
    void wholeSectionOnStringSection() {
        var trace = traceWithOutput("\"just a string\"");

        var replacements = OnlineScoringEngine.toReplacements(Map.of("output", "output"), trace);

        assertThat(replacements).containsEntry("output", "\"just a string\"");
    }

    @Test
    @DisplayName("indexed path against an array section resolves the element")
    void indexedPathOnArraySection() {
        var trace = traceWithOutput("[{\"name\": \"first\"}, {\"name\": \"second\"}]");

        var replacements = OnlineScoringEngine.toReplacements(Map.of("name", "output.[0].name"), trace);

        assertThat(replacements).containsEntry("name", "first");
    }

    @Test
    @DisplayName("a malformed path drops the variable instead of throwing")
    void malformedPath() {
        // The path comes from the rule's variable mapping, so a user typo reaches JsonPath as an
        // unparseable expression (InvalidPathException) rather than a simple miss.
        var trace = traceWithOutput("{\"k\": [{\"x\": 1}]}");
        var variables = Map.of("broken", "output.k[?(@.x");

        assertThatCode(() -> OnlineScoringEngine.toReplacements(variables, trace)).doesNotThrowAnyException();
        assertThat(OnlineScoringEngine.toReplacements(variables, trace)).doesNotContainKey("broken");
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

    @Test
    @DisplayName("a recursive-descent path is dropped at extraction, not evaluated")
    void recursiveDescentIsRejected() {
        // Rules are validated on write, but ones stored before that validation existed still reach the
        // engine, so the grammar is enforced here too: the variable is dropped and scoring continues.
        var trace = traceWithOutput("{\"a\": {\"b\": {\"content\": \"deep\"}}}");
        var variables = Map.of("content", "output..content");

        assertThatCode(() -> OnlineScoringEngine.toReplacements(variables, trace)).doesNotThrowAnyException();
        assertThat(OnlineScoringEngine.toReplacements(variables, trace)).doesNotContainKey("content");
    }

    @Test
    @DisplayName("a single-level wildcard still resolves — only unbounded traversal is rejected")
    void singleLevelWildcardStillResolves() {
        // Shape taken from a rule in the field: results[*].content. Bounded by one level's child count,
        // so it stays supported.
        var trace = traceWithOutput("{\"results\": [{\"content\": \"first\"}, {\"content\": \"second\"}]}");

        var replacements = OnlineScoringEngine.toReplacements(Map.of("all", "output.results[*].content"), trace);

        assertThat(replacements.get("all")).contains("first").contains("second");
    }

    @Test
    @DisplayName("a flat key containing \"$.\" resolves — only the leading prefix is stripped")
    void flatKeyContainingTheRootPrefix() {
        // Stripping every "$." rather than the leading one rewrote the lookup key ("$.a$.b" -> "ab")
        // and missed a property that is present.
        var trace = traceWithOutput("{\"a$.b\": \"present\"}");

        var replacements = OnlineScoringEngine.toReplacements(Map.of("weird", "output.a$.b"), trace);

        assertThat(replacements).containsEntry("weird", "present");
    }
}
