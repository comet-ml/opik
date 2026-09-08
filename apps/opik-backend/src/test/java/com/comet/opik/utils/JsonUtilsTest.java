package com.comet.opik.utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                // non-ASCII: UTF-8 byte length (é=2, CJK=3 each) exceeds the UTF-16 char length, so the
                // char and byte variants must disagree here.
                "{\"m\":\"café-世界\"}",
                "{\"payload\":\"" + "x".repeat(5_000) + "\"}");
    }

    @ParameterizedTest
    @MethodSource("serializableNodes")
    @DisplayName("getSerializedLength: equals the serialized character length for any non-null node")
    void getSerializedLengthMatchesSerializedLength(String json) {
        var node = node(json);
        // The char primitive the token estimate relies on: streaming through CountingWriter must yield
        // exactly the character length of the materialized serialization, at any size/shape.
        assertThat(JsonUtils.getSerializedLength(node)).isEqualTo(JsonUtils.writeValueAsString(node).length());
    }

    @ParameterizedTest
    @MethodSource("serializableNodes")
    @DisplayName("getSerializedLengthInBytes: equals the UTF-8 serialized byte length for any non-null node")
    void getSerializedLengthInBytesMatchesUtf8ByteLength(String json) {
        var node = node(json);
        // The byte primitive the heap cap relies on: it must count UTF-8 bytes (not UTF-16 chars), so the
        // non-ASCII case above yields a strictly larger number than getSerializedLength.
        assertThat(JsonUtils.getSerializedLengthInBytes(node))
                .isEqualTo(JsonUtils.writeValueAsString(node).getBytes(StandardCharsets.UTF_8).length);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullishNodes")
    @DisplayName("getSerializedLengthInBytes: a nullish node counts as 0")
    void getSerializedLengthInBytesNullishIsZero(String description, JsonNode node) {
        assertThat(JsonUtils.getSerializedLengthInBytes(node)).isZero();
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

    @Test
    @DisplayName("exceedsSerializedLengthInBytes: a null value never exceeds the limit")
    void exceedsSerializedLengthNullNeverExceeds() {
        assertThat(JsonUtils.exceedsSerializedLengthInBytes(null, 0L)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"a\":1}", "{\"a\":\"ünïcödé\"}", "[1,2,3]"})
    @DisplayName("exceedsSerializedLengthInBytes: agrees with the exact UTF-8 byte length at the boundary")
    void exceedsSerializedLengthAgreesWithExactLength(String json) {
        var node = node(json);
        long exact = JsonUtils.getSerializedLengthInBytes(node);

        assertThat(JsonUtils.exceedsSerializedLengthInBytes(node, exact)).isFalse();
        assertThat(JsonUtils.exceedsSerializedLengthInBytes(node, exact - 1)).isTrue();
    }

    @Test
    @DisplayName("exceedsSerializedLengthInBytes: works for maps, not only JsonNode")
    void exceedsSerializedLengthSupportsMaps() {
        var map = java.util.Map.<String, Object>of("payload", "x".repeat(1_000));

        assertThat(JsonUtils.exceedsSerializedLengthInBytes(map, 2_000L)).isFalse();
        assertThat(JsonUtils.exceedsSerializedLengthInBytes(map, 100L)).isTrue();
    }

    @Test
    @DisplayName("exceedsSerializedLengthInBytes: rejects an oversized payload without serializing it in full")
    void exceedsSerializedLengthShortCircuits() {
        var value = new ChunkedValue();

        assertThat(JsonUtils.exceedsSerializedLengthInBytes(value, 1_024L)).isTrue();
        // The behaviour under test is the early abort, so assert it directly on how much the serializer
        // actually wrote rather than inferring it from a large fixture. Keeps the test cheap on
        // constrained CI heaps while making the short circuit an explicit assertion instead of a side
        // effect of allocation size.
        assertThat(value.chunksWritten()).isLessThan(ChunkedValue.CHUNKS);
    }

    @Test
    @DisplayName("exceedsSerializedLengthInBytes: propagates serialization failures unrelated to the budget")
    void exceedsSerializedLengthPropagatesUnrelatedFailures() {
        // The budget check catches RuntimeException broadly to unwrap Jackson's wrapping of the abort
        // signal. This guards against that catch swallowing an unrelated failure and reporting it as an
        // oversized value: only BudgetExceededException may return true, everything else must propagate.
        assertThatThrownBy(() -> JsonUtils.exceedsSerializedLengthInBytes(new ExplodingValue(), 1_024L))
                .hasMessageContaining("serializer failed for an unrelated reason");
    }

    /**
     * Serializes to far more than any test budget, one small chunk at a time, and records how many chunks
     * it managed to write before being cut off.
     */
    @JsonSerialize(using = ChunkedValue.Serializer.class)
    static final class ChunkedValue {

        static final int CHUNKS = 10_000;
        private static final String CHUNK = "x".repeat(64);

        private int chunksWritten;

        int chunksWritten() {
            return chunksWritten;
        }

        static final class Serializer extends JsonSerializer<ChunkedValue> {
            @Override
            public void serialize(ChunkedValue value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeStartArray();
                for (int i = 0; i < CHUNKS; i++) {
                    gen.writeString(CHUNK);
                    value.chunksWritten++;
                }
                gen.writeEndArray();
            }
        }
    }

    /** Fails during serialization for a reason that has nothing to do with the size budget. */
    @JsonSerialize(using = ExplodingValue.Serializer.class)
    static final class ExplodingValue {

        static final class Serializer extends JsonSerializer<ExplodingValue> {
            @Override
            public void serialize(ExplodingValue value, JsonGenerator gen, SerializerProvider serializers) {
                throw new IllegalStateException("serializer failed for an unrelated reason");
            }
        }
    }
}
