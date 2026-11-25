package com.comet.opik.utils;

import com.comet.opik.api.resources.utils.PayloadCases;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TruncationUtilsTest {

    @Nested
    @DisplayName("toJsonWithSlim")
    class ToJsonWithSlimTests {

        private static final int MAX = TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH;

        /** Reference slimming, independent of the generator-based one under test. */
        private static JsonNode slim(JsonNode node) {
            return switch (node.getNodeType()) {
                case STRING -> node.textValue().length() > MAX
                        ? TextNode.valueOf(node.textValue().substring(0, MAX) + TruncationUtils.TRUNCATION_SUFFIX)
                        : node;
                case OBJECT -> {
                    ObjectNode result = JsonNodeFactory.instance.objectNode();
                    node.properties().forEach(entry -> result.set(entry.getKey(), slim(entry.getValue())));
                    yield result;
                }
                case ARRAY -> {
                    ArrayNode result = JsonNodeFactory.instance.arrayNode();
                    node.forEach(element -> result.add(slim(element)));
                    yield result;
                }
                case BINARY, BOOLEAN, MISSING, NULL, NUMBER, POJO -> node;
            };
        }

        @ParameterizedTest
        @MethodSource({"com.comet.opik.api.resources.utils.PayloadCases#parsedPayloads",
                "com.comet.opik.api.resources.utils.PayloadCases#builtPayloads"})
        @DisplayName("should truncate the long strings of a payload, keeping every other value")
        void shouldTruncateLongStrings(JsonNode node) {
            JsonWithSlim actual = TruncationUtils.toJsonWithSlim(node);

            var expected = JsonWithSlim.builder()
                    .json(TruncationUtils.toJsonString(node))
                    .slim(JsonUtils.writeValueAsString(slim(node)))
                    .build();
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @DisplayName("should return empty json and slim for null")
        void shouldReturnEmptyForNull() {
            JsonWithSlim actual = TruncationUtils.toJsonWithSlim(null);

            assertThat(actual).isEqualTo(JsonWithSlim.builder().json("").slim("").build());
        }

        @Test
        @DisplayName("should reuse the full json as slim when no string exceeds the max length")
        void shouldReuseJsonWhenNothingIsTruncated() {
            ObjectNode node = JsonNodeFactory.instance.objectNode()
                    .put("text", PayloadCases.randomAlphanumeric(MAX))
                    .put("number", 1);

            JsonWithSlim actual = TruncationUtils.toJsonWithSlim(node);

            assertThat(actual.slim()).isSameAs(actual.json());
        }

        @Test
        @DisplayName("should keep a decimal as the node renders it")
        void shouldKeepTheNodesDecimal() {
            JsonNode node = JsonUtils.valueToTree(Map.of("value", new BigDecimal("10")));

            JsonWithSlim actual = TruncationUtils.toJsonWithSlim(node);

            var expected = JsonWithSlim.builder().json("{\"value\":1E+1}").slim("{\"value\":1E+1}").build();
            assertThat(actual).isEqualTo(expected);
        }
    }
}
