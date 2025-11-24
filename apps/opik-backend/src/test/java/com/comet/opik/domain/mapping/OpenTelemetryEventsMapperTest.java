package com.comet.opik.domain.mapping;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.comet.opik.domain.mapping.OpenTelemetryEventsMapper.processEvents;
import static org.junit.Assert.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OpenTelemetryEventsMapperTest {

    private ObjectNode testNode;

    @BeforeEach
    void setUp() {
        testNode = JsonUtils.createObjectNode();
    }

    @Test
    void testProcessEvents_NullEventsList() {
        // Test with a null events list
        processEvents(null, testNode);

        // Should not add events to metadata
        assertFalse(testNode.has("events"));
    }

    @Test
    void testProcessEvents_EmptyEventsList() {
        // Test with an empty events list
        processEvents(List.of(), testNode);

        // Should not add events to metadata
        assertFalse(testNode.has("events"));
    }

    @Test
    void testProcessEvents_SingleEventWithoutAttributes() {
        // Test with a single event without attributes
        var event = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                .setName("test-event")
                .setTimeUnixNano(1234567890000000L)
                .build();

        processEvents(List.of(event), testNode);

        // Verify events array was added
        assertTrue(testNode.has(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY));
        assertTrue(testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).isArray());
        assertEquals(1, testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).size());

        // Verify event structure
        var eventNode = testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).get(0);
        assertEquals("test-event", eventNode.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText());
        assertEquals(1234567890000000L, eventNode.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong());
        assertFalse(eventNode.has(OpenTelemetryEventsMapper.EVENT_ATTRIBUTES_KEY));
    }

    @Test
    void testProcessEvents_SingleEventWithAttributes() {
        // Test with a single event with attributes
        var event = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                .setName("test-event")
                .setTimeUnixNano(1234567890000000L)
                .addAttributes(KeyValue.newBuilder()
                        .setKey("attr1")
                        .setValue(AnyValue.newBuilder().setStringValue("value1").build())
                        .build())
                .addAttributes(KeyValue.newBuilder()
                        .setKey("attr2")
                        .setValue(AnyValue.newBuilder().setIntValue(42).build())
                        .build())
                .build();

        processEvents(List.of(event), testNode);

        // Verify events array was added
        assertTrue(testNode.has(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY));
        assertEquals(1, testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).size());

        // Verify event structure
        var eventNode = testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).get(0);
        assertEquals("test-event", eventNode.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText());
        assertEquals(1234567890000000L, eventNode.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong());

        // Verify attributes
        assertTrue(eventNode.has(OpenTelemetryEventsMapper.EVENT_ATTRIBUTES_KEY));
        var attributes = eventNode.get(OpenTelemetryEventsMapper.EVENT_ATTRIBUTES_KEY);
        assertEquals("value1", attributes.get("attr1").asText());
        assertEquals(42, attributes.get("attr2").asInt());
    }

    @Test
    void testProcessEvents_MultipleEvents() {
        // Test with multiple events
        var event1 = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                .setName("event-1")
                .setTimeUnixNano(1000000000L)
                .addAttributes(KeyValue.newBuilder()
                        .setKey("key1")
                        .setValue(AnyValue.newBuilder().setStringValue("value1").build())
                        .build())
                .build();

        var event2 = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                .setName("event-2")
                .setTimeUnixNano(2000000000L)
                .build();

        var event3 = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                .setName("event-3")
                .setTimeUnixNano(3000000000L)
                .addAttributes(KeyValue.newBuilder()
                        .setKey("key3")
                        .setValue(AnyValue.newBuilder().setIntValue(123).build())
                        .build())
                .build();

        processEvents(List.of(event1, event2, event3), testNode);

        // Verify events array was added
        assertTrue(testNode.has(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY));
        assertEquals(3, testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).size());

        // Verify first event
        var eventNode1 = testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).get(0);
        assertEquals("event-1", eventNode1.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText());
        assertEquals(1000000000L, eventNode1.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong());
        assertEquals("value1", eventNode1.get(OpenTelemetryEventsMapper.EVENT_ATTRIBUTES_KEY).get("key1").asText());

        // Verify second event (no attributes)
        var eventNode2 = testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).get(1);
        assertEquals("event-2", eventNode2.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText());
        assertEquals(2000000000L, eventNode2.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong());
        assertFalse(eventNode2.has(OpenTelemetryEventsMapper.EVENT_ATTRIBUTES_KEY));

        // Verify third event
        var eventNode3 = testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).get(2);
        assertEquals("event-3", eventNode3.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText());
        assertEquals(3000000000L, eventNode3.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong());
        assertEquals(123, eventNode3.get(OpenTelemetryEventsMapper.EVENT_ATTRIBUTES_KEY).get("key3").asInt());
    }

    @Test
    void testProcessEvents_EventWithDifferentAttributeTypes() {
        // Test event with various attribute types to ensure extractToJsonColumn is called correctly
        var event = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                .setName("complex-event")
                .setTimeUnixNano(1234567890000000L)
                .addAttributes(KeyValue.newBuilder()
                        .setKey("string_attr")
                        .setValue(AnyValue.newBuilder().setStringValue("test-string").build())
                        .build())
                .addAttributes(KeyValue.newBuilder()
                        .setKey("int_attr")
                        .setValue(AnyValue.newBuilder().setIntValue(42).build())
                        .build())
                .addAttributes(KeyValue.newBuilder()
                        .setKey("double_attr")
                        .setValue(AnyValue.newBuilder().setDoubleValue(3.14).build())
                        .build())
                .addAttributes(KeyValue.newBuilder()
                        .setKey("bool_attr")
                        .setValue(AnyValue.newBuilder().setBoolValue(true).build())
                        .build())
                .build();

        processEvents(List.of(event), testNode);

        // Verify event structure
        var eventNode = testNode.get(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY).get(0);
        var attributes = eventNode.get(OpenTelemetryEventsMapper.EVENT_ATTRIBUTES_KEY);

        assertEquals("test-string", attributes.get("string_attr").asText());
        assertEquals(42, attributes.get("int_attr").asInt());
        assertEquals(3.14, attributes.get("double_attr").asDouble(), 0.001);
        assertTrue(attributes.get("bool_attr").asBoolean());
    }

    @Test
    void testProcessEvents_EventWithJsonStringAttribute() {
        // Test event with a JSON string attribute to ensure extractToJsonColumn handles it correctly
        var event = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                .setName("json-event")
                .setTimeUnixNano(1234567890000000L)
                .addAttributes(KeyValue.newBuilder()
                        .setKey("json_attr")
                        .setValue(AnyValue.newBuilder().setStringValue("{\"nested\": \"value\"}").build())
                        .build())
                .build();

        processEvents(List.of(event), testNode);

        // Verify JSON attribute is parsed correctly
        var eventNode = testNode.get("events").get(0);
        var attributes = eventNode.get("attributes");
        assertTrue(attributes.get("json_attr").isObject());
        assertEquals("value", attributes.get("json_attr").get("nested").asText());
    }

    @Test
    void testProcessEvents_PreservesExistingMetadata() {
        // Test that processEvents doesn't overwrite existing metadata
        testNode.put("existing_key", "existing_value");

        var event = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                .setName("test-event")
                .setTimeUnixNano(1234567890000000L)
                .build();

        processEvents(List.of(event), testNode);

        // Verify existing metadata is preserved
        assertEquals("existing_value", testNode.get("existing_key").asText());
        // Verify events were added
        assertTrue(testNode.has(OpenTelemetryEventsMapper.METADATA_EVENTS_KEY));
    }
}
