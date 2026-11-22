package com.comet.opik.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TruncationUtilsTest {

    @Nested
    @DisplayName("createSlimJson with JsonNode")
    class CreateSlimJsonNodeTests {

        static Stream<JsonNode> nullishInputs() {
            return Stream.of(null, NullNode.getInstance());
        }

        @ParameterizedTest
        @MethodSource("nullishInputs")
        @DisplayName("should return NullNode for nullish inputs")
        void shouldReturnNullNodeForNullishInputs(JsonNode input) {
            JsonNode result = TruncationUtils.createSlimJson(input, 100);
            assertThat(result).isNotNull();
            assertThat(result.isNull()).isTrue();
        }

        @Test
        @DisplayName("should not truncate short strings")
        void shouldNotTruncateShortStrings() {
            JsonNode input = JsonUtils.getJsonNodeFromString("{\"key\": \"short value\"}");
            JsonNode result = TruncationUtils.createSlimJson(input, 100);

            assertThat(result.get("key").asText()).isEqualTo("short value");
        }

        @Test
        @DisplayName("should truncate long strings with suffix")
        void shouldTruncateLongStringsWithSuffix() {
            String longValue = "a".repeat(150);
            JsonNode input = JsonUtils.getJsonNodeFromString("{\"key\": \"" + longValue + "\"}");
            JsonNode result = TruncationUtils.createSlimJson(input, 100);

            String truncatedValue = result.get("key").asText();
            assertThat(truncatedValue).hasSize(103); // 100 + "..."
            assertThat(truncatedValue).endsWith("...");
            assertThat(truncatedValue).startsWith("a".repeat(100));
        }

        @Test
        @DisplayName("should preserve all keys in nested objects")
        void shouldPreserveAllKeysInNestedObjects() {
            String json = """
                    {
                        "level1": {
                            "level2": {
                                "shortKey": "short",
                                "longKey": "%s"
                            },
                            "anotherKey": "value"
                        },
                        "topLevel": "data"
                    }
                    """.formatted("x".repeat(200));

            JsonNode input = JsonUtils.getJsonNodeFromString(json);
            JsonNode result = TruncationUtils.createSlimJson(input, 50);

            // All keys should be present
            assertThat(result.has("level1")).isTrue();
            assertThat(result.has("topLevel")).isTrue();
            assertThat(result.get("level1").has("level2")).isTrue();
            assertThat(result.get("level1").has("anotherKey")).isTrue();
            assertThat(result.get("level1").get("level2").has("shortKey")).isTrue();
            assertThat(result.get("level1").get("level2").has("longKey")).isTrue();

            // Short values unchanged
            assertThat(result.get("level1").get("level2").get("shortKey").asText()).isEqualTo("short");
            assertThat(result.get("level1").get("anotherKey").asText()).isEqualTo("value");
            assertThat(result.get("topLevel").asText()).isEqualTo("data");

            // Long value truncated
            String longKeyValue = result.get("level1").get("level2").get("longKey").asText();
            assertThat(longKeyValue).hasSize(53); // 50 + "..."
            assertThat(longKeyValue).endsWith("...");
        }

        @Test
        @DisplayName("should handle arrays with mixed content")
        void shouldHandleArraysWithMixedContent() {
            String json = """
                    {
                        "items": [
                            "short",
                            "%s",
                            {"nested": "object"},
                            42,
                            true,
                            null
                        ]
                    }
                    """.formatted("y".repeat(100));

            JsonNode input = JsonUtils.getJsonNodeFromString(json);
            JsonNode result = TruncationUtils.createSlimJson(input, 20);

            JsonNode items = result.get("items");
            assertThat(items.isArray()).isTrue();
            assertThat(items.size()).isEqualTo(6);

            // Short string unchanged
            assertThat(items.get(0).asText()).isEqualTo("short");

            // Long string truncated
            assertThat(items.get(1).asText()).hasSize(23); // 20 + "..."
            assertThat(items.get(1).asText()).endsWith("...");

            // Nested object preserved
            assertThat(items.get(2).has("nested")).isTrue();
            assertThat(items.get(2).get("nested").asText()).isEqualTo("object");

            // Number unchanged
            assertThat(items.get(3).asInt()).isEqualTo(42);

            // Boolean unchanged
            assertThat(items.get(4).asBoolean()).isTrue();

            // Null unchanged
            assertThat(items.get(5).isNull()).isTrue();
        }

        @Test
        @DisplayName("should pass through numbers unchanged")
        void shouldPassThroughNumbersUnchanged() {
            JsonNode input = JsonUtils.getJsonNodeFromString("{\"int\": 42, \"float\": 3.14, \"big\": 9999999999999}");
            JsonNode result = TruncationUtils.createSlimJson(input, 10);

            assertThat(result.get("int").asInt()).isEqualTo(42);
            assertThat(result.get("float").asDouble()).isEqualTo(3.14);
            assertThat(result.get("big").asLong()).isEqualTo(9999999999999L);
        }

        @Test
        @DisplayName("should pass through booleans unchanged")
        void shouldPassThroughBooleansUnchanged() {
            JsonNode input = JsonUtils.getJsonNodeFromString("{\"yes\": true, \"no\": false}");
            JsonNode result = TruncationUtils.createSlimJson(input, 10);

            assertThat(result.get("yes").asBoolean()).isTrue();
            assertThat(result.get("no").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("should handle empty objects and arrays")
        void shouldHandleEmptyObjectsAndArrays() {
            JsonNode input = JsonUtils.getJsonNodeFromString("{\"emptyObj\": {}, \"emptyArr\": []}");
            JsonNode result = TruncationUtils.createSlimJson(input, 10);

            assertThat(result.get("emptyObj").isObject()).isTrue();
            assertThat(result.get("emptyObj").size()).isZero();
            assertThat(result.get("emptyArr").isArray()).isTrue();
            assertThat(result.get("emptyArr").size()).isZero();
        }
    }

    @Nested
    @DisplayName("createSlimJsonString")
    class CreateSlimJsonStringTests {

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should return input for null or empty strings")
        void shouldReturnInputForNullOrEmpty(String input) {
            assertThat(TruncationUtils.createSlimJsonString(input, 100)).isEqualTo(input);
        }

        @Test
        @DisplayName("should truncate valid JSON string")
        void shouldTruncateValidJsonString() {
            String longValue = "z".repeat(500);
            String input = "{\"data\": \"" + longValue + "\"}";
            String result = TruncationUtils.createSlimJsonString(input, 100);

            JsonNode parsed = JsonUtils.getJsonNodeFromString(result);
            assertThat(parsed.get("data").asText()).hasSize(103);
            assertThat(parsed.get("data").asText()).endsWith("...");
        }

        @Test
        @DisplayName("should fall back to simple truncation for invalid JSON")
        void shouldFallBackForInvalidJson() {
            String notJson = "This is not JSON, just a plain string that is quite long";
            String result = TruncationUtils.createSlimJsonString(notJson, 20);

            assertThat(result).hasSize(23); // 20 + "..."
            assertThat(result).isEqualTo("This is not JSON, ju...");
        }

        @Test
        @DisplayName("should use default max length when not specified")
        void shouldUseDefaultMaxLength() {
            String longValue = "w".repeat(2000);
            String input = "{\"data\": \"" + longValue + "\"}";
            String result = TruncationUtils.createSlimJsonString(input);

            JsonNode parsed = JsonUtils.getJsonNodeFromString(result);
            // Default is 1000 + "..."
            assertThat(parsed.get("data").asText()).hasSize(1003);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "[1, 2, 3]",
                "\"just a string\"",
                "42",
                "true",
                "null"
        })
        @DisplayName("should handle various JSON root types")
        void shouldHandleVariousJsonRootTypes(String input) {
            String result = TruncationUtils.createSlimJsonString(input, 100);
            // Should not throw and should produce valid output
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("Real-world scenarios")
    class RealWorldScenarioTests {

        @Test
        @DisplayName("should handle typical LLM output with messages array")
        void shouldHandleTypicalLlmOutput() {
            String json = """
                    {
                        "messages": [
                            {"role": "user", "content": "%s"},
                            {"role": "assistant", "content": "%s"}
                        ],
                        "model": "gpt-4",
                        "usage": {"prompt_tokens": 100, "completion_tokens": 500}
                    }
                    """.formatted("q".repeat(5000), "a".repeat(10000));

            JsonNode input = JsonUtils.getJsonNodeFromString(json);
            JsonNode result = TruncationUtils.createSlimJson(input, 1000);

            // Structure preserved
            assertThat(result.has("messages")).isTrue();
            assertThat(result.has("model")).isTrue();
            assertThat(result.has("usage")).isTrue();

            // Messages array structure preserved
            assertThat(result.get("messages").isArray()).isTrue();
            assertThat(result.get("messages").size()).isEqualTo(2);
            assertThat(result.get("messages").get(0).has("role")).isTrue();
            assertThat(result.get("messages").get(0).has("content")).isTrue();

            // Long content truncated
            assertThat(result.get("messages").get(0).get("content").asText()).hasSize(1003);
            assertThat(result.get("messages").get(1).get("content").asText()).hasSize(1003);

            // Short values unchanged
            assertThat(result.get("model").asText()).isEqualTo("gpt-4");
            assertThat(result.get("usage").get("prompt_tokens").asInt()).isEqualTo(100);
        }

        @Test
        @DisplayName("should produce smaller output than input for large payloads")
        void shouldProduceSmallerOutputForLargePayloads() {
            String largeValue = "x".repeat(50000);
            String input = "{\"big\": \"" + largeValue + "\", \"small\": \"tiny\"}";
            String result = TruncationUtils.createSlimJsonString(input, 1000);

            assertThat(result.length()).isLessThan(input.length());
            assertThat(result.length()).isLessThan(2000); // Much smaller than 50KB
        }
    }
}
