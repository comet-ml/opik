package com.comet.opik.domain.mapping;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

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
    void testExtractToJsonColumn_InvalidJsonString() {
        // Test with an invalid JSON string that starts with { but is malformed
        String invalidJson = "Analyze this text";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertThatCode(() -> extractToJsonColumn(testNode, "testKey", anyValue)).doesNotThrowAnyException();

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asText()).isEqualTo("Analyze this text");
    }

    @Test
    void testExtractToJsonColumn_PlainString() {
        // Test with plain string that doesn't look like JSON
        String plainString = "This is a plain string";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(plainString).build();

        extractToJsonColumn(testNode, "testKey", anyValue);

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asText()).isEqualTo("This is a plain string");
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

    @Test
    void testExtractToJsonColumn_StringThatLooksLikeJsonButIsInvalid() {
        // Test with string that starts with { but is not valid JSON
        String invalidJson = "{Analyze this text}";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertThatCode(() -> extractToJsonColumn(testNode, "testKey", anyValue)).doesNotThrowAnyException();

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asText()).isEqualTo("{Analyze this text}");
    }

    @Test
    void testExtractToJsonColumn_StringThatLooksLikeJsonArrayButIsInvalid() {
        // Test with string that starts with [ but is not valid JSON
        String invalidJson = "[Analyze this text]";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertThatCode(() -> extractToJsonColumn(testNode, "testKey", anyValue)).doesNotThrowAnyException();

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asText()).isEqualTo("[Analyze this text]");
    }

    @Test
    void testExtractToJsonColumn_ExactAnalyzeTokenError() {
        // Test the exact scenario that caused the original error:
        // "Unrecognized token 'Analyze': was expecting (JSON String, Number (or 'NaN'/'+INF'/'-INF'), Array, Object or token 'null', 'true' or 'false')"
        String invalidJson = "Analyze";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertThatCode(() -> extractToJsonColumn(testNode, "testKey", anyValue)).doesNotThrowAnyException();

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asText()).isEqualTo("Analyze");
    }

    @Test
    void testExtractToJsonColumn_StringStartingWithAnalyze() {
        // Test with string that starts with "Analyze" but is not valid JSON
        String invalidJson = "Analyze this content";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertThatCode(() -> extractToJsonColumn(testNode, "testKey", anyValue)).doesNotThrowAnyException();

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asText()).isEqualTo("Analyze this content");
    }

    @Test
    void testExtractToJsonColumn_StringThatLooksLikeJsonObjectButStartsWithAnalyze() {
        // Test with string that looks like JSON object but starts with Analyze
        String invalidJson = "{Analyze: \"value\"}";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertThatCode(() -> extractToJsonColumn(testNode, "testKey", anyValue)).doesNotThrowAnyException();

        assertThat(testNode.has("testKey")).isTrue();
        assertThat(testNode.get("testKey").asText()).isEqualTo("{Analyze: \"value\"}");
    }

    // Tests for extractUsageField method

    @Test
    void testExtractUsageField_IntegerValue() {
        // Test extracting usage from integer value
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.input_tokens";
        AnyValue value = AnyValue.newBuilder().setIntValue(42).build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry("prompt_tokens", 42);
    }

    @Test
    void testExtractUsageField_IntegerValue_OutputTokens() {
        // Test extracting usage from integer value for output tokens
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.output_tokens";
        AnyValue value = AnyValue.newBuilder().setIntValue(24).build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry("completion_tokens", 24);
    }

    @Test
    void testExtractUsageField_IntegerValue_UnmappedKey() {
        // Test extracting usage from integer value for unmapped key
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.total_tokens";
        AnyValue value = AnyValue.newBuilder().setIntValue(66).build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry("total_tokens", 66);
    }

    @Test
    void testExtractUsageField_StringValueValidInteger() {
        // Test extracting usage from string value that contains valid integer
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.input_tokens";
        AnyValue value = AnyValue.newBuilder().setStringValue("12").build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry("prompt_tokens", 12);
    }

    @Test
    void testExtractUsageField_StringValueValidInteger_OutputTokens() {
        // Test extracting usage from string value for output tokens
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.output_tokens";
        AnyValue value = AnyValue.newBuilder().setStringValue("36").build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry("completion_tokens", 36);
    }

    @Test
    void testExtractUsageField_StringValueInvalidInteger_FallsBackToJsonParsing() {
        // Test extracting usage from string value that's not a valid integer - should fall back to JSON parsing
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.metrics";
        String jsonString = "{\"input_tokens\": 20, \"output_tokens\": 30}";
        AnyValue value = AnyValue.newBuilder().setStringValue(jsonString).build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry("prompt_tokens", 20);
        assertThat(usage).containsEntry("completion_tokens", 30);
    }

    @Test
    void testExtractUsageField_JsonObjectWithUsageData() {
        // Test extracting usage from JSON object string
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
    void testExtractUsageField_NestedJsonString() {
        // Test extracting usage from nested JSON string (string containing JSON string)
        Map<String, Integer> usage = new HashMap<>();
        OpenTelemetryMappingRule rule = OpenTelemetryMappingRule.builder()
                .rule("gen_ai.usage.")
                .isPrefix(true)
                .source("GenAI")
                .outcome(OpenTelemetryMappingRule.Outcome.USAGE)
                .build();

        String key = "gen_ai.usage.nested";
        String innerJson = "{\"input_tokens\": 80, \"output_tokens\": 40}";
        String nestedJson = "\"" + innerJson.replace("\"", "\\\"") + "\"";
        AnyValue value = AnyValue.newBuilder().setStringValue(nestedJson).build();

        extractUsageField(usage, rule, key, value);

        assertThat(usage).containsEntry("prompt_tokens", 80);
        assertThat(usage).containsEntry("completion_tokens", 40);
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
        // Test extracting usage from empty JSON object
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

    @Test
    void testExtractTags_CommaSeparatedString() {
        // Test extracting tags from comma-separated string
        String tagsString = "machine-learning, nlp, chatbot";
        AnyValue value = AnyValue.newBuilder().setStringValue(tagsString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_CommaSeparatedStringWithSpaces() {
        // Test extracting tags from comma-separated string with irregular spacing
        String tagsString = "  machine-learning  ,nlp,  chatbot  , ai  ";
        AnyValue value = AnyValue.newBuilder().setStringValue(tagsString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot", "ai");
    }

    @Test
    void testExtractTags_CommaSeparatedStringWithEmptyValues() {
        // Test extracting tags from comma-separated string with empty values
        String tagsString = "machine-learning,,nlp,   ,chatbot,";
        AnyValue value = AnyValue.newBuilder().setStringValue(tagsString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_SingleTag() {
        // Test extracting single tag
        String tagsString = "machine-learning";
        AnyValue value = AnyValue.newBuilder().setStringValue(tagsString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning");
    }

    @Test
    void testExtractTags_EmptyString() {
        // Test extracting tags from empty string
        String tagsString = "";
        AnyValue value = AnyValue.newBuilder().setStringValue(tagsString).build();

        var result = extractTags(value);

        assertThat(result).isEmpty();
    }

    @Test
    void testExtractTags_OnlyCommasAndSpaces() {
        // Test extracting tags from string with only commas and spaces
        String tagsString = " , , ,   ";
        AnyValue value = AnyValue.newBuilder().setStringValue(tagsString).build();

        var result = extractTags(value);

        assertThat(result).isEmpty();
    }

    @Test
    void testExtractTags_JsonArrayString() {
        // Test extracting tags from a JSON array string
        String jsonArrayString = "[\"machine-learning\", \"nlp\", \"chatbot\"]";
        AnyValue value = AnyValue.newBuilder().setStringValue(jsonArrayString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_JsonArrayStringWithSpaces() {
        // Test extracting tags from a JSON array string with spaces in values
        String jsonArrayString = "[\"  machine-learning  \", \"nlp\", \"  chatbot  \"]";
        AnyValue value = AnyValue.newBuilder().setStringValue(jsonArrayString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_JsonArrayStringWithEmptyValues() {
        // Test extracting tags from a JSON array string with empty values
        String jsonArrayString = "[\"machine-learning\", \"\", \"nlp\", \"   \", \"chatbot\"]";
        AnyValue value = AnyValue.newBuilder().setStringValue(jsonArrayString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_EmptyJsonArray() {
        // Test extracting tags from an empty JSON array
        String jsonArrayString = "[]";
        AnyValue value = AnyValue.newBuilder().setStringValue(jsonArrayString).build();

        var result = extractTags(value);

        assertThat(result).isEmpty();
    }

    @Test
    void testExtractTags_JsonArrayStringWithNonStringValues() {
        // Test extracting tags from a JSON array string with non-string values (should be ignored)
        String jsonArrayString = "[\"machine-learning\", 42, \"nlp\", true, \"chatbot\"]";
        AnyValue value = AnyValue.newBuilder().setStringValue(jsonArrayString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_InvalidJsonArrayString_FallsBackToCommaSeparated() {
        // Test extracting tags from invalid JSON array string - should fall back to comma-separated parsing
        String invalidJsonArray = "[machine-learning, nlp, chatbot]";
        AnyValue value = AnyValue.newBuilder().setStringValue(invalidJsonArray).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("[machine-learning", "nlp", "chatbot]");
    }

    @Test
    void testExtractTags_StringThatStartsWithBracketButNotArray() {
        // Test extracting tags from a string that starts with [ but is not a valid array
        String notArrayString = "[This is not an array";
        AnyValue value = AnyValue.newBuilder().setStringValue(notArrayString).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("[This is not an array");
    }

    @Test
    void testExtractTags_ArrayValue() {
        // Test extracting tags from OpenTelemetry ArrayValue
        var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                .addValues(AnyValue.newBuilder().setStringValue("machine-learning").build())
                .addValues(AnyValue.newBuilder().setStringValue("nlp").build())
                .addValues(AnyValue.newBuilder().setStringValue("chatbot").build())
                .build();
        AnyValue value = AnyValue.newBuilder().setArrayValue(arrayValue).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_ArrayValueWithSpaces() {
        // Test extracting tags from OpenTelemetry ArrayValue with spaces
        var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                .addValues(AnyValue.newBuilder().setStringValue("  machine-learning  ").build())
                .addValues(AnyValue.newBuilder().setStringValue("nlp").build())
                .addValues(AnyValue.newBuilder().setStringValue("  chatbot  ").build())
                .build();
        AnyValue value = AnyValue.newBuilder().setArrayValue(arrayValue).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_ArrayValueWithEmptyValues() {
        // Test extracting tags from OpenTelemetry ArrayValue with empty values
        var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                .addValues(AnyValue.newBuilder().setStringValue("machine-learning").build())
                .addValues(AnyValue.newBuilder().setStringValue("").build())
                .addValues(AnyValue.newBuilder().setStringValue("nlp").build())
                .addValues(AnyValue.newBuilder().setStringValue("   ").build())
                .addValues(AnyValue.newBuilder().setStringValue("chatbot").build())
                .build();
        AnyValue value = AnyValue.newBuilder().setArrayValue(arrayValue).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_ArrayValueWithNonStringValues() {
        // Test extracting tags from OpenTelemetry ArrayValue with non-string values (should be ignored)
        var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                .addValues(AnyValue.newBuilder().setStringValue("machine-learning").build())
                .addValues(AnyValue.newBuilder().setIntValue(42).build())
                .addValues(AnyValue.newBuilder().setStringValue("nlp").build())
                .addValues(AnyValue.newBuilder().setBoolValue(true).build())
                .addValues(AnyValue.newBuilder().setStringValue("chatbot").build())
                .build();
        AnyValue value = AnyValue.newBuilder().setArrayValue(arrayValue).build();

        var result = extractTags(value);

        assertThat(result).containsExactly("machine-learning", "nlp", "chatbot");
    }

    @Test
    void testExtractTags_EmptyArrayValue() {
        // Test extracting tags from empty OpenTelemetry ArrayValue
        var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder().build();
        AnyValue value = AnyValue.newBuilder().setArrayValue(arrayValue).build();

        var result = extractTags(value);

        assertThat(result).isEmpty();
    }

    @Test
    void testExtractTags_UnsupportedValueType() {
        // Test extracting tags from an unsupported value type (should return an empty list)
        AnyValue value = AnyValue.newBuilder().setIntValue(42).build();

        var result = extractTags(value);

        assertThat(result).isEmpty();
    }

    @Test
    void testExtractTags_BooleanValue() {
        // Test extracting tags from boolean value (should return an empty list)
        AnyValue value = AnyValue.newBuilder().setBoolValue(true).build();

        var result = extractTags(value);

        assertThat(result).isEmpty();
    }

    @Test
    void testExtractTags_DoubleValue() {
        // Test extracting tags from double value (should return an empty list)
        AnyValue value = AnyValue.newBuilder().setDoubleValue(3.14).build();

        var result = extractTags(value);

        assertThat(result).isEmpty();
    }
}
