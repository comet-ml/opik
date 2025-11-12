package com.comet.opik.domain;

import com.comet.opik.api.Span.SpanBuilder;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import jakarta.ws.rs.BadRequestException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@UtilityClass
@Slf4j
public class OpenTelemetryMapper {

    private static Map<String, String> USAGE_KEYS_MAPPING = Map.of(
            "input_tokens", "prompt_tokens",
            "output_tokens", "completion_tokens");

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

        enrichSpanWithAttributes(spanBuilder, otelSpan.getAttributesList(), integrationName);

        return spanBuilder.build();
    }

    /**
     * Extracts whats relevant from the messy KeyValues list adding the values into input/output/metadata/model/usage.
     *
     * @param spanBuilder the span builder where we will be injecting the extracted values
     * @param attributes the list of span attributes extracted from the otel payload
     * @param integrationName the name of the integration sending the spans (can be empty)
     */
    public static void enrichSpanWithAttributes(SpanBuilder spanBuilder, List<KeyValue> attributes,
            String integrationName) {
        Map<String, Integer> usage = new HashMap<>();
        ObjectNode input = JsonUtils.createObjectNode();
        ObjectNode output = JsonUtils.createObjectNode();
        ObjectNode metadata = JsonUtils.createObjectNode();

        if (StringUtils.isNotEmpty(integrationName)) {
            metadata.put("integration", integrationName);
        }

        // Iterate over each attribute key-value pair
        attributes.forEach(attribute -> {
            var key = attribute.getKey();
            var value = attribute.getValue();

            OpenTelemetryMappingRule.findRule(key).ifPresentOrElse(rule -> {
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
                }
            }, () -> {
                // if it's not explicitly request to drop, we keep it in input
                log.debug("No rule found for kv {} -> {}. Using for Input.", key, attribute.getValue());
                extractToJsonColumn(input, key, value);
            });
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

    static void extractToJsonColumn(ObjectNode node, String key, AnyValue value) {
        switch (value.getValueCase()) {
            case STRING_VALUE -> {
                var stringValue = value.getStringValue();
                // check if string value is actually a string or a stringfied json
                if (stringValue.startsWith("\"") || stringValue.startsWith("[")
                        || stringValue.startsWith("{")) {
                    try {
                        var jsonNode = JsonUtils.getJsonNodeFromString(stringValue);
                        if (jsonNode.isTextual()) {
                            try {
                                jsonNode = JsonUtils.getJsonNodeFromString(jsonNode.asText());
                            } catch (UncheckedIOException e) {
                                log.warn("Failed to parse nested JSON string for key {}: {}. Using as plain text.",
                                        key, e.getMessage());
                                node.put(key, jsonNode.asText());
                                return;
                            }
                        }
                        node.set(key, jsonNode);
                    } catch (UncheckedIOException e) {
                        log.warn("Failed to parse JSON string for key {}: {}. Using as plain text.", key,
                                e.getMessage());
                        node.put(key, stringValue);
                    }
                } else {
                    node.put(key, stringValue);
                }
            }
            case INT_VALUE -> node.put(key, value.getIntValue());
            case DOUBLE_VALUE -> node.put(key, value.getDoubleValue());
            case BOOL_VALUE -> node.put(key, value.getBoolValue());
            case ARRAY_VALUE -> {
                var array = JsonUtils.createArrayNode();
                value.getArrayValue().getValuesList().forEach(val -> array.add(val.getStringValue()));
                node.set(key, array);
            }
            default -> log.warn("Unsupported attribute: {} -> {}", key, value);
        }
    }

    private static void extractUsageField(Map<String, Integer> usage, OpenTelemetryMappingRule rule, String key,
            AnyValue value) {
        // usage might appear as single int values or an json object
        if (value.hasIntValue()) {
            var actualKey = key.substring(rule.getRule().length());
            usage.put(USAGE_KEYS_MAPPING.getOrDefault(actualKey, actualKey), (int) value.getIntValue());
        } else {
            try {
                JsonNode usageNode = JsonUtils.getJsonNodeFromString(value.getStringValue());
                if (usageNode.isTextual()) {
                    try {
                        usageNode = JsonUtils.getJsonNodeFromString(usageNode.asText());
                    } catch (UncheckedIOException e) {
                        log.warn(
                                "Failed to parse nested JSON string for usage field {}: {}. Skipping usage extraction.",
                                key, e.getMessage());
                        return;
                    }
                }

                // we expect only integers for usage fields
                usageNode.properties().forEach(entry -> {
                    if (entry.getValue().isNumber()) {
                        usage.put(USAGE_KEYS_MAPPING.getOrDefault(entry.getKey(), entry.getKey()),
                                entry.getValue().intValue());
                    } else {
                        log.warn("Unrecognized usage attribute {} -> {}", entry.getKey(), entry.getValue());
                    }
                });
            } catch (UncheckedIOException ex) {
                log.warn("Failed to parse JSON string for usage field {}: {}. Skipping usage extraction.", key,
                        ex.getMessage());
                throw new BadRequestException(
                        "Failed to parse JSON string for usage field " + key + " ->" + ex.getMessage());
            }
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
