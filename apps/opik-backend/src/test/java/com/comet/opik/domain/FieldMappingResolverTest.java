package com.comet.opik.domain;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldMappingResolverTest {

    private static final String ENTITY = """
            {
              "id": "01a0a54f-a84b-7b14-8088-7cf0bdfd4e07",
              "duration": 1.42,
              "input": {
                "input_text": "Bonjour, comment allez-vous ?",
                "messages": [{"role": "user", "content": "translate"}, {"role": "system", "content": "be terse"}]
              },
              "output": {"verdict": "correct", "score": 0.94},
              "metadata": {"reviewer": {"team": "loc-qa"}},
              "tags": ["translation", "qa"],
              "feedback_scores": [{"name": "Correctness", "value": 0.94}]
            }
            """;

    private static JsonNode entity() {
        return JsonUtils.getJsonNodeFromString(ENTITY);
    }

    private static Map<String, JsonNode> resolve(Map<String, String> fieldMappings) {
        return FieldMappingResolver.resolve(entity(), fieldMappings);
    }

    @Nested
    @DisplayName("Resolving paths:")
    class ResolvingPaths {

        @Test
        @DisplayName("Success: resolves a nested path to its scalar value")
        void resolvesNestedPath() {
            var result = resolve(Map.of("input", "input.input_text"));

            assertThat(result).containsOnlyKeys("input");
            assertThat(result.get("input").asText()).isEqualTo("Bonjour, comment allez-vous ?");
        }

        @Test
        @DisplayName("Success: resolves indexed access into an array")
        void resolvesIndexedAccess() {
            var result = resolve(Map.of("first_message", "input.messages[0].content"));

            assertThat(result.get("first_message").asText()).isEqualTo("translate");
        }

        @Test
        @DisplayName("Success: a bare root resolves to the whole object")
        void resolvesBareRoot() {
            var result = resolve(Map.of("whole_input", "input"));

            assertThat(result.get("whole_input").get("input_text").asText())
                    .isEqualTo("Bonjour, comment allez-vous ?");
        }

        @Test
        @DisplayName("Success: resolves a top-level scalar field")
        void resolvesTopLevelScalar() {
            var result = resolve(Map.of("took_ms", "duration"));

            assertThat(result.get("took_ms").asDouble()).isEqualTo(1.42);
        }

        @Test
        @DisplayName("Success: resolves several mappings at once")
        void resolvesSeveralMappings() {
            var result = resolve(Map.of(
                    "input", "input.input_text",
                    "expected_output", "output.verdict",
                    "team", "metadata.reviewer.team"));

            assertThat(result).containsOnlyKeys("input", "expected_output", "team");
            assertThat(result.get("expected_output").asText()).isEqualTo("correct");
            assertThat(result.get("team").asText()).isEqualTo("loc-qa");
        }

        @Test
        @DisplayName("Success: a wildcard that matches resolves to the matched values")
        void wildcardThatMatchesResolves() {
            var result = resolve(Map.of("roles", "input.messages[*].role"));

            assertThat(result.get("roles")).hasSize(2);
            assertThat(result.get("roles").get(0).asText()).isEqualTo("user");
        }

        @Test
        @DisplayName("Success: an array field resolves to the array")
        void resolvesArrayField() {
            var result = resolve(Map.of("tags", "tags"));

            assertThat(result.get("tags")).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Omitting fields:")
    class OmittingFields {

        @Test
        @DisplayName("Success: a path that is absent yields no entry")
        void absentPathYieldsNoEntry() {
            var result = resolve(Map.of("tone", "input.tone"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Success: an unknown root yields no entry")
        void unknownRootYieldsNoEntry() {
            var result = resolve(Map.of("nope", "not_a_field.at_all"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Success: an out of bounds index yields no entry")
        void outOfBoundsIndexYieldsNoEntry() {
            var result = resolve(Map.of("third", "input.messages[9].content"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Success: resolved fields survive alongside unresolved ones")
        void resolvedFieldsSurviveUnresolvedOnes() {
            var result = resolve(Map.of("input", "input.input_text", "tone", "input.tone"));

            assertThat(result).containsOnlyKeys("input");
        }

        @Test
        @DisplayName("Success: a wildcard that matches nothing yields no entry, not an empty array")
        void wildcardThatMatchesNothingYieldsNoEntry() {
            var result = resolve(Map.of("tones", "input.messages[*].tone"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Success: an empty array field yields no entry")
        void emptyArrayFieldYieldsNoEntry() {
            var result = FieldMappingResolver.resolve(
                    JsonUtils.getJsonNodeFromString("{\"tags\": []}"), Map.of("tags", "tags"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Success: an empty mapping resolves to an empty map")
        void emptyMappingResolvesToEmptyMap() {
            assertThat(resolve(Map.of())).isEmpty();
        }

        @Test
        @DisplayName("Success: a scalar entity has no paths to walk")
        void scalarEntityHasNoPaths() {
            var result = FieldMappingResolver.resolve(
                    JsonUtils.getJsonNodeFromString("\"just a string\""), Map.of("x", "input.y"));

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("Unsupported constructs:")
    class UnsupportedConstructs {

        @Test
        @DisplayName("Success: recursive descent is dropped rather than evaluated")
        void recursiveDescentIsDropped() {
            var result = resolve(Map.of("anything", "input..content"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Success: a filter predicate is dropped rather than evaluated")
        void filterPredicateIsDropped() {
            var result = resolve(Map.of("score", "feedback_scores[?(@.name == 'Correctness')].value"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Success: a malformed path yields no entry rather than propagating")
        void malformedPathYieldsNoEntry() {
            var result = resolve(Map.of("bucket", "input.[[["));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Success: a supported mapping still resolves alongside a dropped one")
        void supportedMappingResolvesAlongsideDroppedOne() {
            var result = resolve(Map.of("input", "input.input_text", "bad", "input..content"));

            assertThat(result).containsOnlyKeys("input");
        }
    }
}
