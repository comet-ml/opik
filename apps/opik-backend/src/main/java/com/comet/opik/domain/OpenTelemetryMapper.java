package com.comet.opik.domain;

import com.comet.opik.api.Span.SpanBuilder;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.OpenTelemetryMappingRule.Outcome.*;

@UtilityClass
@Slf4j
public class OpenTelemetryMapper {

    /**
     * Converts an OpenTelemetry Span into an Opik Span. Despite similar conceptually, but require some translation
     * of concepts, especially around ids.
     *
     * We will be linking this span to a given Opik traceId precalculated with the closest timestamp we could get for
     * it. We can extract the timestamp from this traceId and use into spanId otel -> opik conversion, so all span ids
     * for the trace can be predictable, but using the same reference timestamp.
     *
     * @param otelSpan an OpenTelemetry Span
     * @param opikTraceId the Opik UUID to be used for this span
     * @return a converted Opik Span
     */
    public static com.comet.opik.api.Span toOpikSpan(Span otelSpan, UUID opikTraceId) {
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

        enrichSpanWithAttributes(spanBuilder, otelSpan.getAttributesList());

        log.info("builder: {}", spanBuilder);

        return spanBuilder.build();
    }

    private static void enrichSpanWithAttributes(SpanBuilder spanBuilder, List<KeyValue> attributes) {
        ObjectNode input = JsonUtils.MAPPER.createObjectNode();
        ObjectNode output = JsonUtils.MAPPER.createObjectNode();
        ObjectNode metadata = JsonUtils.MAPPER.createObjectNode();
        Map<String, Integer> usage = new HashMap<>();

        Set<OpenTelemetryMappingRule.Outcome> jsonOutcomes = Set.of(INPUT, OUTPUT, METADATA);

        // Iterate over each attribute key-value pair
        attributes.forEach(attribute -> {
            var key = attribute.getKey();
            var value = attribute.getValue();

            Optional<OpenTelemetryMappingRule> hasRule = OpenTelemetryMappingRule.findRule(key);
            hasRule.ifPresentOrElse(rule -> {
                if (rule.getOutcome().equals(USAGE)) {
                    JsonNode usageNode = JsonUtils.getJsonNodeFromString(value.getStringValue());
                    if (usageNode.isTextual()) {
                        usageNode = JsonUtils.getJsonNodeFromString(usageNode.asText());
                    }
                    usageNode.fields().forEachRemaining(entry -> {
                        if (entry.getValue().isNumber()) {
                            usage.put(entry.getKey(), entry.getValue().intValue());
                        } else
                            log.warn("Unrecognized attribute {}: {}", entry.getKey(), entry.getValue());
                    });
                }

                if (jsonOutcomes.contains(rule.getOutcome())) {
                    ObjectNode node;
                    node = switch (rule.getOutcome()) {
                        case INPUT -> input;
                        case OUTPUT -> output;
                        default -> metadata;
                    };

                    switch (value.getValueCase()) {
                        case STRING_VALUE -> {
                            var stringValue = value.getStringValue();
                            if (stringValue.startsWith("\"") || stringValue.startsWith("[")
                                    || stringValue.startsWith("{")) {
                                var jsonNode = JsonUtils.getJsonNodeFromString(stringValue);
                                if (jsonNode.isTextual()) {
                                    jsonNode = JsonUtils.getJsonNodeFromString(jsonNode.asText());
                                }
                                node.set(key, jsonNode);
                            } else
                                node.put(key, stringValue);
                        }
                        case INT_VALUE -> node.put(key, value.getIntValue());
                        case DOUBLE_VALUE -> node.put(key, value.getDoubleValue());
                        case BOOL_VALUE -> node.put(key, value.getBoolValue());
                        default -> log.warn("Unsupported attribute: {}", attribute);
                    }
                }
            }, () -> log.info("No rule found for key: {} (value: {})", key, attribute.getValue()));
        });

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
    }

    /**
     * Uses 64-bit integer OpenTelemetry SpanId and its timestamp to prepare a good UUIDv7 id. This is actually
     * a good UUIDv7 (in opposition of the traceId) as its composed from an id and a timestamp, so spans will be
     * properly ordered in the span table.
     *
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
        // Get the 64 most significant bits.
        long msb = uuid.getMostSignificantBits();
        // The top 48 bits represent the timestamp.
        return msb >>> 16;
    }
}
