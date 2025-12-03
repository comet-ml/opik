package com.comet.opik.domain.mapping;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractToJsonColumn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
}
