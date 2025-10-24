package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OpenTelemetryMapperTest {

    private ObjectNode testNode;

    @BeforeEach
    void setUp() {
        testNode = JsonUtils.createObjectNode();
    }

    @Test
    void testExtractToJsonColumn_ValidJsonString() {
        // Test with valid JSON string
        String validJson = "{\"key\": \"value\"}";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(validJson).build();

        OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);

        assertTrue(testNode.has("testKey"));
        assertTrue(testNode.get("testKey").isObject());
        assertEquals("value", testNode.get("testKey").get("key").asText());
    }

    @Test
    void testExtractToJsonColumn_InvalidJsonString() {
        // Test with invalid JSON string that starts with { but is malformed
        String invalidJson = "Analyze this text";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertDoesNotThrow(() -> {
            OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);
        });

        assertTrue(testNode.has("testKey"));
        assertEquals("Analyze this text", testNode.get("testKey").asText());
    }

    @Test
    void testExtractToJsonColumn_PlainString() {
        // Test with plain string that doesn't look like JSON
        String plainString = "This is a plain string";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(plainString).build();

        OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);

        assertTrue(testNode.has("testKey"));
        assertEquals("This is a plain string", testNode.get("testKey").asText());
    }

    @Test
    void testExtractToJsonColumn_IntValue() {
        AnyValue anyValue = AnyValue.newBuilder().setIntValue(42).build();

        OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);

        assertTrue(testNode.has("testKey"));
        assertEquals(42, testNode.get("testKey").asInt());
    }

    @Test
    void testExtractToJsonColumn_DoubleValue() {
        AnyValue anyValue = AnyValue.newBuilder().setDoubleValue(3.14).build();

        OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);

        assertTrue(testNode.has("testKey"));
        assertEquals(3.14, testNode.get("testKey").asDouble(), 0.001);
    }

    @Test
    void testExtractToJsonColumn_BoolValue() {
        AnyValue anyValue = AnyValue.newBuilder().setBoolValue(true).build();

        OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);

        assertTrue(testNode.has("testKey"));
        assertTrue(testNode.get("testKey").asBoolean());
    }

    @Test
    void testExtractToJsonColumn_ArrayValue() {
        var arrayValue = io.opentelemetry.proto.common.v1.ArrayValue.newBuilder()
                .addValues(AnyValue.newBuilder().setStringValue("item1").build())
                .addValues(AnyValue.newBuilder().setStringValue("item2").build())
                .build();
        AnyValue anyValue = AnyValue.newBuilder().setArrayValue(arrayValue).build();

        OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);

        assertTrue(testNode.has("testKey"));
        assertTrue(testNode.get("testKey").isArray());
        assertEquals(2, testNode.get("testKey").size());
        assertEquals("item1", testNode.get("testKey").get(0).asText());
        assertEquals("item2", testNode.get("testKey").get(1).asText());
    }

    @Test
    void testExtractToJsonColumn_StringThatLooksLikeJsonButIsInvalid() {
        // Test with string that starts with { but is not valid JSON
        String invalidJson = "{Analyze this text}";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertDoesNotThrow(() -> {
            OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);
        });

        assertTrue(testNode.has("testKey"));
        assertEquals("{Analyze this text}", testNode.get("testKey").asText());
    }

    @Test
    void testExtractToJsonColumn_StringThatLooksLikeJsonArrayButIsInvalid() {
        // Test with string that starts with [ but is not valid JSON
        String invalidJson = "[Analyze this text]";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertDoesNotThrow(() -> {
            OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);
        });

        assertTrue(testNode.has("testKey"));
        assertEquals("[Analyze this text]", testNode.get("testKey").asText());
    }

    @Test
    void testExtractToJsonColumn_ExactAnalyzeTokenError() {
        // Test the exact scenario that caused the original error:
        // "Unrecognized token 'Analyze': was expecting (JSON String, Number (or 'NaN'/'+INF'/'-INF'), Array, Object or token 'null', 'true' or 'false')"
        String invalidJson = "Analyze";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertDoesNotThrow(() -> {
            OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);
        });

        assertTrue(testNode.has("testKey"));
        assertEquals("Analyze", testNode.get("testKey").asText());
    }

    @Test
    void testExtractToJsonColumn_StringStartingWithAnalyze() {
        // Test with string that starts with "Analyze" but is not valid JSON
        String invalidJson = "Analyze this content";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertDoesNotThrow(() -> {
            OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);
        });

        assertTrue(testNode.has("testKey"));
        assertEquals("Analyze this content", testNode.get("testKey").asText());
    }

    @Test
    void testExtractToJsonColumn_StringThatLooksLikeJsonObjectButStartsWithAnalyze() {
        // Test with string that looks like JSON object but starts with Analyze
        String invalidJson = "{Analyze: \"value\"}";
        AnyValue anyValue = AnyValue.newBuilder().setStringValue(invalidJson).build();

        // Should not throw exception, should store as plain text
        assertDoesNotThrow(() -> {
            OpenTelemetryMapper.extractToJsonColumn(testNode, "testKey", anyValue);
        });

        assertTrue(testNode.has("testKey"));
        assertEquals("{Analyze: \"value\"}", testNode.get("testKey").asText());
    }

    @Test
    void testThreadIdMappingRule() {
        // Test that thread_id attribute is mapped to metadata
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("thread_id")
                        .setValue(AnyValue.newBuilder().setStringValue("test-thread-123"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null);

        var span = spanBuilder.build();

        // Verify thread_id is stored in metadata
        assertTrue(span.metadata().has("thread_id"));
        assertEquals("test-thread-123", span.metadata().get("thread_id").asText());
    }
}
