package com.comet.opik.domain.mapping;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.trace.v1.Span;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;
import java.util.Objects;

/**
 * Mapper for processing OpenTelemetry span events and adding them to metadata.
 * Handles conversion of OpenTelemetry events into JSON format for storage in span metadata.
 */
@UtilityClass
public class OpenTelemetryEventsMapper {

    /**
     * Key used to store events array in metadata.
     */
    public static final String METADATA_EVENTS_KEY = "opentelemetry.events";

    /**
     * Key used to store event name in event node.
     */
    public static final String EVENT_NAME_KEY = "name";

    /**
     * Key used to store event timestamp in event node.
     */
    public static final String EVENT_TIME_UNIX_NANO_KEY = "time_unix_nano";

    /**
     * Key used to store event attributes in the event node.
     */
    public static final String EVENT_ATTRIBUTES_KEY = "attributes";

    /**
     * Processes OpenTelemetry span events and adds them to the metadata object.
     *
     * @param events   the list of events extracted from the otel payload
     * @param metadata the metadata object where events will be added
     */
    public static void processEvents(List<Span.Event> events, ObjectNode metadata) {
        if (CollectionUtils.isEmpty(events)) {
            return;
        }
        Objects.requireNonNull(metadata);

        var eventsArray = JsonUtils.createArrayNode();
        events.forEach(event -> {
            var eventNode = JsonUtils.createObjectNode();
            eventNode.put(EVENT_NAME_KEY, event.getName());
            eventNode.put(EVENT_TIME_UNIX_NANO_KEY, event.getTimeUnixNano());

            // Process event attributes
            if (!CollectionUtils.isEmpty(event.getAttributesList())) {
                var eventAttributes = JsonUtils.createObjectNode();
                event.getAttributesList().forEach(attribute -> OpenTelemetryMappingUtils.extractToJsonColumn(
                        eventAttributes, attribute.getKey(), attribute.getValue()));
                eventNode.set(EVENT_ATTRIBUTES_KEY, eventAttributes);
            }

            eventsArray.add(eventNode);
        });
        metadata.set(METADATA_EVENTS_KEY, eventsArray);
    }
}
