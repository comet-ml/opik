package com.comet.opik.domain.mapping;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.comet.opik.domain.mapping.OpenTelemetryEventsMapper.EVENT_ATTRIBUTES_KEY;
import static com.comet.opik.domain.mapping.OpenTelemetryEventsMapper.METADATA_EVENTS_KEY;
import static com.comet.opik.domain.mapping.OpenTelemetryEventsMapper.processEvents;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

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
        assertThat(testNode.has(METADATA_EVENTS_KEY)).isFalse();
    }

    @Test
    void testProcessEvents_EmptyEventsList() {
        // Test with an empty events list
        processEvents(List.of(), testNode);

        // Should not add events to metadata
        assertThat(testNode.has(METADATA_EVENTS_KEY)).isFalse();
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
        assertThat(testNode.has(METADATA_EVENTS_KEY)).isTrue();
        assertThat(testNode.get(METADATA_EVENTS_KEY).isArray()).isTrue();
        assertThat(testNode.get(METADATA_EVENTS_KEY).size()).isEqualTo(1);

        // Verify event structure
        var eventNode = testNode.get(METADATA_EVENTS_KEY).get(0);
        assertThat(eventNode.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText()).isEqualTo("test-event");
        assertThat(eventNode.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong())
                .isEqualTo(1234567890000000L);
        assertThat(eventNode.has(EVENT_ATTRIBUTES_KEY)).isFalse();
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
        assertThat(testNode.has(METADATA_EVENTS_KEY)).isTrue();
        assertThat(testNode.get(METADATA_EVENTS_KEY).size()).isEqualTo(1);

        // Verify event structure
        var eventNode = testNode.get(METADATA_EVENTS_KEY).get(0);
        assertThat(eventNode.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText()).isEqualTo("test-event");
        assertThat(eventNode.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong())
                .isEqualTo(1234567890000000L);

        // Verify attributes
        assertThat(eventNode.has(EVENT_ATTRIBUTES_KEY)).isTrue();
        var attributes = eventNode.get(EVENT_ATTRIBUTES_KEY);
        assertThat(attributes.get("attr1").asText()).isEqualTo("value1");
        assertThat(attributes.get("attr2").asInt()).isEqualTo(42);
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
        assertThat(testNode.has(METADATA_EVENTS_KEY)).isTrue();
        assertThat(testNode.get(METADATA_EVENTS_KEY).size()).isEqualTo(3);

        // Verify first event
        var eventNode1 = testNode.get(METADATA_EVENTS_KEY).get(0);
        assertThat(eventNode1.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText()).isEqualTo("event-1");
        assertThat(eventNode1.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong()).isEqualTo(1000000000L);
        assertThat(eventNode1.get(EVENT_ATTRIBUTES_KEY).get("key1").asText()).isEqualTo("value1");

        // Verify second event (no attributes)
        var eventNode2 = testNode.get(METADATA_EVENTS_KEY).get(1);
        assertThat(eventNode2.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText()).isEqualTo("event-2");
        assertThat(eventNode2.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong()).isEqualTo(2000000000L);
        assertThat(eventNode2.has(EVENT_ATTRIBUTES_KEY)).isFalse();

        // Verify third event
        var eventNode3 = testNode.get(METADATA_EVENTS_KEY).get(2);
        assertThat(eventNode3.get(OpenTelemetryEventsMapper.EVENT_NAME_KEY).asText()).isEqualTo("event-3");
        assertThat(eventNode3.get(OpenTelemetryEventsMapper.EVENT_TIME_UNIX_NANO_KEY).asLong()).isEqualTo(3000000000L);
        assertThat(eventNode3.get(EVENT_ATTRIBUTES_KEY).get("key3").asInt()).isEqualTo(123);
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
        var eventNode = testNode.get(METADATA_EVENTS_KEY).get(0);
        var attributes = eventNode.get(EVENT_ATTRIBUTES_KEY);

        assertThat(attributes.get("string_attr").asText()).isEqualTo("test-string");
        assertThat(attributes.get("int_attr").asInt()).isEqualTo(42);
        assertThat(attributes.get("double_attr").asDouble()).isEqualTo(3.14, offset(0.001));
        assertThat(attributes.get("bool_attr").asBoolean()).isTrue();
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
        var eventNode = testNode.get(METADATA_EVENTS_KEY).get(0);
        var attributes = eventNode.get(EVENT_ATTRIBUTES_KEY);
        assertThat(attributes.get("json_attr").isObject()).isTrue();
        assertThat(attributes.get("json_attr").get("nested").asText()).isEqualTo("value");
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
        assertThat(testNode.get("existing_key").asText()).isEqualTo("existing_value");
        // Verify events were added
        assertThat(testNode.has(METADATA_EVENTS_KEY)).isTrue();
    }
}
