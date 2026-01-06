package com.comet.opik.domain;

import com.comet.opik.api.Span.SpanBuilder;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRuleFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.mapping.OpenTelemetryEventsMapper.processEvents;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractTags;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractToJsonColumn;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractUsageField;

@UtilityClass
@Slf4j
public class OpenTelemetryMapper {

    /**
     * Converts an OpenTelemetry Span into an Opik Span. Despite similar conceptually, but require some translation
     * of concepts, especially around ids.
     * <p>
     * We will be linking this span to a given Opik traceId precalculated with the closest timestamp we could get for
     * it. We can extract the timestamp from this traceId and use into spanId otel -> opik conversion, so all span ids
     * for the trace can be predictable, but using the same reference timestamp.
     *
     * @param otelSpan        an OpenTelemetry Span
     * @param opikTraceId     the Opik UUID to be used for this span
     * @param integrationName the detected (if any) integration name
     * @return a converted Opik Span
     */
    public static com.comet.opik.api.Span toOpikSpan(Span otelSpan, UUID opikTraceId, String integrationName) {
        var traceTimestamp = extractTimestampFromUUIDv7(opikTraceId);

        var startTimeMs = Duration.ofNanos(otelSpan.getStartTimeUnixNano()).toMillis();
        var endTimeMs = Duration.ofNanos(otelSpan.getEndTimeUnixNano()).toMillis();

        var otelSpanId = otelSpan.getSpanId();
        var opikSpanId = convertOtelIdToUUIDv7(otelSpanId.toByteArray(), traceTimestamp);

        var otelParentSpanId = otelSpan.getParentSpanId();
        var opikParentSpanId = otelParentSpanId.isEmpty()
                ? null
                : convertOtelIdToUUIDv7(otelParentSpanId.toByteArray(), traceTimestamp);

        var spanBuilder = com.comet.opik.api.Span.builder()
                .id(opikSpanId)
                .traceId(opikTraceId)
                .parentSpanId(opikParentSpanId)
                .name(otelSpan.getName())
                .type(SpanType.general)
                .startTime(Instant.ofEpochMilli(startTimeMs))
                .endTime(Instant.ofEpochMilli(endTimeMs));

        List<Span.Event> events = otelSpan.getEventsList();
        enrichSpanWithAttributes(spanBuilder, otelSpan.getAttributesList(), integrationName, events);

        return spanBuilder.build();
    }

    /**
     * Extracts whats relevant from the messy KeyValues list adding the values into input/output/metadata/model/usage.
     *
     * @param spanBuilder the span builder where we will be injecting the extracted values
     * @param attributes the list of span attributes extracted from the otel payload
     * @param integrationName the name of the integration sending the spans (can be empty)
     * @param events the list of events extracted from the otel payload
     */
    public static void enrichSpanWithAttributes(SpanBuilder spanBuilder, List<KeyValue> attributes,
            String integrationName, List<Span.Event> events) {
        Map<String, Integer> usage = new HashMap<>();
        ObjectNode input = JsonUtils.createObjectNode();
        ObjectNode output = JsonUtils.createObjectNode();
        ObjectNode metadata = JsonUtils.createObjectNode();
        Set<String> tags = new HashSet<>();

        if (StringUtils.isNotBlank(integrationName)) {
            metadata.put("integration", integrationName);
        }

        // Iterate over each attribute key-value pair
        attributes.forEach(attribute -> {
            var key = attribute.getKey();
            var value = attribute.getValue();

            OpenTelemetryMappingRuleFactory.findRule(key).ifPresentOrElse(rule -> {
                Optional.ofNullable(rule.getSpanType()).ifPresent(spanBuilder::type);

                switch (rule.getOutcome()) {
                    case MODEL :
                        spanBuilder.model(value.getStringValue());
                        break;

                    case PROVIDER :
                        spanBuilder.provider(value.getStringValue());
                        break;

                    case USAGE :
                        extractUsageField(usage, rule, key, value);
                        break;

                    case INPUT :
                    case OUTPUT :
                    case METADATA :
                        ObjectNode node;
                        node = switch (rule.getOutcome()) {
                            case INPUT -> input;
                            case OUTPUT -> output;
                            default -> metadata;
                        };

                        extractToJsonColumn(node, key, value);
                        break;

                    case TAGS :
                        List<String> span_tags = extractTags(value);
                        if (CollectionUtils.isNotEmpty(span_tags)) {
                            tags.addAll(span_tags);
                        }
                        break;

                    case THREAD_ID :
                        // Store as 'thread_id' in metadata for trace grouping
                        // First value wins if multiple attributes map to THREAD_ID
                        if (!metadata.has("thread_id")) {
                            extractToJsonColumn(metadata, "thread_id", value);
                        }
                        break;

                    case DROP :
                        // Explicitly drop this attribute
                        break;
                }
            }, () -> {
                // if it's not explicitly request to drop, we keep it in input
                log.debug("No rule found for kv {} -> {}. Using for Input.", key, attribute.getValue());
                extractToJsonColumn(input, key, value);
            });
        });

        // Process events and add them to metadata
        processEvents(events, metadata);

        if (!metadata.isEmpty()) {
            spanBuilder.metadata(metadata);
        }
        if (!output.isEmpty()) {
            spanBuilder.output(output);
        }
        if (!input.isEmpty()) {
            spanBuilder.input(input);
        }
        if (!usage.isEmpty()) {
            spanBuilder.usage(usage);
        }
        if (!tags.isEmpty()) {
            spanBuilder.tags(tags);
        }
    }

    /**
     * Uses 64-bit integer OpenTelemetry SpanId and its timestamp to prepare a good UUIDv7 id. This is actually
     * a good UUIDv7 (in opposition of the traceId) as its composed from an id and a timestamp, so spans will be
     * properly ordered in the span table.
     * The truncate timestamp option is relevant when you receive non-UUIDs in multiple batches and can't predict
     * what's going to be the actual Opik UUID from the Otel integer id you know. So we take the span timestamp truncated
     * by time window as form to make it predictable. This works fine as makes UUID predictable and they are stored next
     * to each other on Clickhouse, but it has two drawbacks: (1) traces might show up un-ordered in Traces page (a trace
     * from Monday can appear as 'newer' than a Friday trace as their UUID have the same timestamp: Sunday at 00:00:00;
     * (2) a routine running between Saturday 23:59:30 and Sunday 00:00:30 will be split in 2 traces; both incomplete.
     *
     * @param otelSpanId a OpenTelemetry 64-bit integer spanId
     * @param timestampMs a timestamp for the span in millis
     * @return a valid UUIDv7
     */
    public static UUID convertOtelIdToUUIDv7(byte[] otelSpanId, long timestampMs) {
        // Prepare the 16-byte array for the UUID
        byte[] uuidBytes = new byte[16];

        // Bytes 0-5: 48-bit timestamp (big-endian)
        long ts48 = timestampMs & 0xFFFFFFFFFFFFL; // 48 bits
        uuidBytes[0] = (byte) ((ts48 >> 40) & 0xFF);
        uuidBytes[1] = (byte) ((ts48 >> 32) & 0xFF);
        uuidBytes[2] = (byte) ((ts48 >> 24) & 0xFF);
        uuidBytes[3] = (byte) ((ts48 >> 16) & 0xFF);
        uuidBytes[4] = (byte) ((ts48 >> 8) & 0xFF);
        uuidBytes[5] = (byte) (ts48 & 0xFF);

        // Bytes 6-15: 80 bits derived from the spanId hash
        // Hash the spanId (8 bytes) using SHA-256 and take the first 10 bytes (80 bits)
        byte[] hash = DigestUtils.sha256(otelSpanId);
        System.arraycopy(hash, 0, uuidBytes, 6, 10);

        // Set the version to 7 (stored in the high nibble of byte 6)
        uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0F) | 0x70);
        // Set the variant (the two most-significant bits of byte 8 should be 10)
        uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3F) | 0x80);

        // Build the UUID from the byte array
        ByteBuffer byteBuffer = ByteBuffer.wrap(uuidBytes);
        long mostSigBits = byteBuffer.getLong(); // FYI: it reads and change offset
        long leastSigBits = byteBuffer.getLong();
        return new UUID(mostSigBits, leastSigBits);
    }

    /**
     * Extracts the Unix epoch timestamp in milliseconds from a UUIDv7.
     *
     * @param uuid the UUIDv7 instance
     * @return the extracted timestamp as a long (milliseconds since Unix epoch)
     */
    private long extractTimestampFromUUIDv7(UUID uuid) {
        return IdGenerator.extractTimestampFromUUIDv7(uuid).toEpochMilli();
    }
}
