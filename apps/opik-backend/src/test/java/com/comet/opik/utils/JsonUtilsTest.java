package com.comet.opik.utils;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JsonUtilsTest {

    private static JsonNode node(String json) {
        return JsonUtils.getJsonNodeFromString(json);
    }

    static Stream<String> serializableNodes() {
        return Stream.of(
                "{}",
                "[]",
                "{\"a\":1}",
                "{\"a\":\"hello\",\"b\":[1,2,3],\"c\":{\"d\":true,\"e\":null}}",
                "[1,2,3,\"x\"]",
                "\"a scalar string\"",
                "42",
                "3.14",
                "true",
                "{\"payload\":\"" + "x".repeat(5_000) + "\"}");
    }

    @ParameterizedTest
    @MethodSource("serializableNodes")
    @DisplayName("getSerializedLength: equals the serialized character length for any non-null node")
    void getSerializedLengthMatchesSerializedLength(String json) {
        var node = node(json);
        // The primitive both the size estimate and the byte cap rely on: streaming through CountingWriter
        // must yield exactly the length of the materialized serialization, at any size/shape.
        assertThat(JsonUtils.getSerializedLength(node)).isEqualTo(JsonUtils.writeValueAsString(node).length());
    }

    static Stream<Arguments> nullishNodes() {
        // Both branches of the zero-guard: a Java null reference and a JSON null node. Neither contributes
        // to the size (a JSON null would otherwise serialize to the 4 chars of "null").
        return Stream.of(
                Arguments.of("null reference", null),
                Arguments.of("JSON null node", node("null")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullishNodes")
    @DisplayName("getSerializedLength: a nullish node counts as 0, not its serialized form")
    void getSerializedLengthNullishIsZero(String description, JsonNode node) {
        assertThat(JsonUtils.getSerializedLength(node)).isZero();
    }

    @Test
    @DisplayName("merge: object overrides are added/replaced while existing keys are preserved")
    void mergeObjectPreservesAndOverrides() {
        var base = JsonUtils.getJsonNodeFromString(
                JsonUtils.writeValueAsString(Map.of("optimizer", "MetaPrompt", "model", "gpt-4o")));
        var overrides = JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(
                Map.of("model", "gpt-4o-mini", "scoring_health", Map.of("failed_count", 2, "total_count", 5))));

        var merged = JsonUtils.merge(base, overrides);

        assertThat(merged.isObject()).isTrue();
        assertThat(merged.get("optimizer").asText()).isEqualTo("MetaPrompt"); // preserved
        assertThat(merged.get("model").asText()).isEqualTo("gpt-4o-mini"); // overridden
        assertThat(merged.get("scoring_health").get("failed_count").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("merge: null overrides returns base unchanged")
    void mergeNullOverridesReturnsBase() {
        var base = node("{\"a\":1}");
        assertThat(JsonUtils.merge(base, null)).isSameAs(base);
    }

    @Test
    @DisplayName("merge: scalar/array overrides are ignored so no non-object reaches storage")
    void mergeNonObjectOverridesIgnored() {
        var base = node("{\"a\":1}");
        assertThat(JsonUtils.merge(base, node("\"scalar\""))).isSameAs(base);
        assertThat(JsonUtils.merge(base, node("[1,2]"))).isSameAs(base);
    }

    @Test
    @DisplayName("merge: a non-object base is discarded, yielding just the object overrides")
    void mergeNonObjectBaseDiscarded() {
        var merged = JsonUtils.merge(node("\"scalar\""), node("{\"a\":1}"));
        assertThat(merged.isObject()).isTrue();
        assertThat(merged.get("a").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("merge: null base with scalar overrides never yields a non-object")
    void mergeNullBaseScalarOverride() {
        assertThat(JsonUtils.merge(null, node("\"scalar\""))).isNull();
    }
}
