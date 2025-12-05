package com.comet.opik.domain.mapping;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractTags;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractToJsonColumn;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractUsageField;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

public class OpenTelemetryMappingUtilsTest {
    private ObjectNode testNode;

    @BeforeEach
    void setUp() {
        testNode = JsonUtils.createObjectNode();
    }

    @Test
    void testExtractToJsonColumn_ValidJsonString() {
        // Test with a valid JSON string
        String validJson = "{\"key\": \"value\"}";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(validJson).build();

        extractToJsonColumn(testNode, "testKey", anyValue);

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").isObject()).isTrue();
        assertThat(testNode.get("testKey").get("key").asText()).isEqualTo("value");
    }

    @Test
    void testExtractToJsonColumn_IntValue() {
        AnyValue anyValue = AnyValue.newBuilder().setIntValue(42).build();

        extractToJsonColumn(testNode, "testKey", anyValue);

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asInt()).isEqualTo(42);
    }

    @Test
    void testExtractToJsonColumn_DoubleValue() {
        AnyValue anyValue = AnyValue.newBuilder().setDoubleValue(3.14).build();

        extractToJsonColumn(testNode, "testKey", anyValue);

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asDouble()).isEqualTo(3.14, offset(0.001));
    }

    @Test
    void testExtractToJsonColumn_BoolValue() {
        AnyValue anyValue = AnyValue.newBuilder().setBoolValue(true).build();

        extractToJsonColumn(testNode, "testKey", anyValue);

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asBoolean()).isTrue();
    }

    @Test
    void testExtractToJsonColumn_ArrayValue() {
        var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                .addValues(AnyValue.newBuilder().setStringValue("item1").build())
                .addValues(AnyValue.newBuilder().setStringValue("item2").build())
                .build();
        AnyValue anyValue = AnyValue.newBuilder().setArrayValue(arrayValue).build();

        extractToJsonColumn(testNode, "testKey", anyValue);

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").isArray()).isTrue();
        assertThat(testNode.get("testKey").size()).isEqualTo(2);
        assertThat(testNode.get("testKey").get(0).asText()).isEqualTo("item1");
        assertThat(testNode.get("testKey").get(1).asText()).isEqualTo("item2");
    }

    private static Stream<Arguments> provideInvalidJsonStringsForExtractToJsonColumn() {
        return Stream.of(
                Arguments.of("{Analyze this text}", "string that starts with { but contains unquoted Analyze"),
                Arguments.of("Analyze", "exact Analyze token that caused the original error"),
                Arguments.of("Analyze this content", "string that starts with Analyze but is not valid JSON"),
                Arguments.of("{Analyze: \"value\"}",
                        "string that looks like JSON object but starts with unquoted Analyze"),
                Arguments.of("[Analyze this text]", "string that looks like JSON array but is not valid"));
    }

    @ParameterizedTest
    @MethodSource("provideInvalidJsonStringsForExtractToJsonColumn")
    void testExtractToJsonColumn_InvalidJsonStrings(String invalidJson, String description) {
        // Test strings that are not valid JSON - should not throw exception, should store as plain text
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        assertThatCode(() -> extractToJsonColumn(testNode, "testKey", anyValue)).doesNotThrowAnyException();

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asText()).isEqualTo(invalidJson);
    }

    // Tests for extractUsageField method

    private static Stream<Arguments> provideUsageFieldSimpleValues() {
        return Stream.of(
                // Integer value tests
                Arguments.of("gen_ai.usage.input_tokens", 42, "prompt_tokens", 42, "integer input_tokens"),
                Arguments.of("gen_ai.usage.output_tokens", 24, "completion_tokens", 24, "integer output_tokens"),
                Arguments.of("gen_ai.usage.total_tokens", 66, "total_tokens", 66, "integer unmapped key"),
                // String value tests
                Arguments.of("gen_ai.usage.input_tokens", "12", "prompt_tokens", 12, "string input_tokens"),
                Arguments.of("gen_ai.usage.output_tokens", "36", "completion_tokens", 36, "string output_tokens"));
    }

    @ParameterizedTest
    @MethodSource("provideUsageFieldSimpleValues")
    void testExtractUsageField_SimpleValues(String key, Object valueData, String expectedKey, int expectedValue,
            String description) {
        // Test extracting usage from simple integer or string values
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        AnyValue value;
        if (valueData instanceof Integer) {
            value = AnyValue.newBuilder().setIntValue((Integer) valueData).build();
        } else {
            value = AnyValue.newBuilder().setStringValue((String) valueData).build();
        }

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry(expectedKey, expectedValue);
    }

    @Test
    void testExtractUsageField_JsonObjectWithUsageData() {
        // Test extracting usage from a nested JSON object string
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.details";
        String jsonString = "{\"input_tokens\": 100, \"output_tokens\": 50, \"total_tokens\": 150}";
        AnyValue value = AnyValue.newBuilder().setStringValue(jsonString).build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry("prompt_tokens", 100);
        assertThat(usage).containsEntry("completion_tokens", 50);
        assertThat(usage).containsEntry("total_tokens", 150);
    }

    @Test
    void testExtractUsageField_JsonObjectWithMixedTypes() {
        // Test extracting usage from JSON object string with mixed types (should only extract integers)
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.mixed";
        String jsonString = "{\"input_tokens\": 75, \"model_name\": \"gpt-4\", \"output_tokens\": 25}";
        AnyValue value = AnyValue.newBuilder().setStringValue(jsonString).build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry("prompt_tokens", 75);
        assertThat(usage).containsEntry("completion_tokens", 25);
        assertThat(usage).hasSize(2); // Only the integer values should be extracted
    }

    @Test
    void testExtractUsageField_InvalidJsonString_ThrowsBadRequestException() {
        // Test extracting usage from invalid JSON string - should throw BadRequestException
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.invalid";
        String invalidJson = "not a valid integer and not valid json {";
        AnyValue value = AnyValue.newBuilder().setStringValue(invalidJson).build();

        assertThatThrownBy(() -> extractUsageField(usage, rule, key, value))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Failed to parse JSON string for usage field");
    }

    @Test
    void testExtractUsageField_EmptyJsonObject() {
        // Test extracting usage from an empty JSON object
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.empty";
        String emptyJson = "{}";
        AnyValue value = AnyValue.newBuilder().setStringValue(emptyJson).build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).isEmpty();
    }

    // Tests for extractTags method

    private static Stream<Arguments> provideStringTagValues() {
        return Stream.of(
                // Comma-separated strings
                Arguments.of("machine-learning, nlp, chatbot",
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "comma-separated string"),
                Arguments.of("  machine-learning  ,nlp,  chatbot  , ai  ",
                        new String[]{"machine-learning", "nlp", "chatbot", "ai"},
                        "comma-separated string with irregular spacing"),
                Arguments.of("machine-learning,,nlp,   ,chatbot,",
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "comma-separated string with empty values"),
                Arguments.of("machine-learning",
                        new String[]{"machine-learning"},
                        "single tag"),
                Arguments.of("",
                        new String[]{},
                        "empty string"),
                Arguments.of(" , , ,   ",
                        new String[]{},
                        "string with only commas and spaces"),
                // JSON array strings
                Arguments.of("[\"machine-learning\", \"nlp\", \"chatbot\"]",
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "JSON array string"),
                Arguments.of("[\"  machine-learning  \", \"nlp\", \"  chatbot  \"]",
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "JSON array string with spaces in values"),
                Arguments.of("[\"machine-learning\", \"\", \"nlp\", \"   \", \"chatbot\"]",
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "JSON array string with empty values"),
                Arguments.of("[]",
                        new String[]{},
                        "empty JSON array"),
                Arguments.of("[\"machine-learning\", 42, \"nlp\", true, \"chatbot\"]",
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "JSON array string with non-string values"),
                // Invalid JSON - falls back to comma-separated
                Arguments.of("[machine-learning, nlp, chatbot]",
                        new String[]{"[machine-learning", "nlp", "chatbot]"},
                        "invalid JSON array - falls back to comma-separated"),
                Arguments.of("[This is not an array",
                        new String[]{"[This is not an array"},
                        "string that starts with bracket but is not an array"));
    }

    @ParameterizedTest
    @MethodSource("provideStringTagValues")
    void testExtractTags_StringValues(String tagsString, String[] expectedTags, String description) {
        // Test extracting tags from various string formats
        AnyValue value = AnyValue.newBuilder().setStringValue(tagsString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly(expectedTags);
    }

    private static Stream<Arguments> provideArrayTagValues() {
        return Stream.of(
                // Simple array with 3 string values
                Arguments.of(
                        (Supplier<AnyValue>) () -> {
                            var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                                    .addValues(AnyValue.newBuilder().setStringValue("machine-learning").build())
                                    .addValues(AnyValue.newBuilder().setStringValue("nlp").build())
                                    .addValues(AnyValue.newBuilder().setStringValue("chatbot").build())
                                    .build();
                            return AnyValue.newBuilder().setArrayValue(arrayValue).build();
                        },
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "OpenTelemetry ArrayValue"),
                // Array with strings that have leading/trailing spaces
                Arguments.of(
                        (Supplier<AnyValue>) () -> {
                            var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                                    .addValues(AnyValue.newBuilder().setStringValue("  machine-learning  ").build())
                                    .addValues(AnyValue.newBuilder().setStringValue("nlp").build())
                                    .addValues(AnyValue.newBuilder().setStringValue("  chatbot  ").build())
                                    .build();
                            return AnyValue.newBuilder().setArrayValue(arrayValue).build();
                        },
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "OpenTelemetry ArrayValue with spaces"),
                // Array with empty strings that should be filtered out
                Arguments.of(
                        (Supplier<AnyValue>) () -> {
                            var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                                    .addValues(AnyValue.newBuilder().setStringValue("machine-learning").build())
                                    .addValues(AnyValue.newBuilder().setStringValue("").build())
                                    .addValues(AnyValue.newBuilder().setStringValue("nlp").build())
                                    .addValues(AnyValue.newBuilder().setStringValue("   ").build())
                                    .addValues(AnyValue.newBuilder().setStringValue("chatbot").build())
                                    .build();
                            return AnyValue.newBuilder().setArrayValue(arrayValue).build();
                        },
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "OpenTelemetry ArrayValue with empty values"),
                // Array with mixed types (strings, int, boolean) - non-strings should be ignored
                Arguments.of(
                        (Supplier<AnyValue>) () -> {
                            var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                                    .addValues(AnyValue.newBuilder().setStringValue("machine-learning").build())
                                    .addValues(AnyValue.newBuilder().setIntValue(42).build())
                                    .addValues(AnyValue.newBuilder().setStringValue("nlp").build())
                                    .addValues(AnyValue.newBuilder().setBoolValue(true).build())
                                    .addValues(AnyValue.newBuilder().setStringValue("chatbot").build())
                                    .build();
                            return AnyValue.newBuilder().setArrayValue(arrayValue).build();
                        },
                        new String[]{"machine-learning", "nlp", "chatbot"},
                        "OpenTelemetry ArrayValue with non-string values"));
    }

    @ParameterizedTest
    @MethodSource("provideArrayTagValues")
    void testExtractTags_ArrayValues(Supplier<AnyValue> valueSupplier, String[] expectedTags, String description) {
        // Test extracting tags from OpenTelemetry ArrayValue in various scenarios
        AnyValue value = valueSupplier.get();

        var result = extractTags(value);

        assertThat(result).containsExactly(expectedTags);
    }

    private static Stream<Arguments> provideUnsupportedTagValues() {
        var emptyArrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder().build();

        return Stream.of(
                Arguments.of(AnyValue.newBuilder().setArrayValue(emptyArrayValue).build(), "empty ArrayValue"),
                Arguments.of(AnyValue.newBuilder().setIntValue(42).build(), "integer value"),
                Arguments.of(AnyValue.newBuilder().setBoolValue(true).build(), "boolean value"),
                Arguments.of(AnyValue.newBuilder().setDoubleValue(3.14).build(), "double value"));
    }

    @ParameterizedTest
    @MethodSource("provideUnsupportedTagValues")
    void testExtractTags_UnsupportedValues(AnyValue value, String description) {
        // Test extracting tags from unsupported value types - should return an empty list
        var result = extractTags(value);

        assertThat(result).isEmpty();
    }
}
