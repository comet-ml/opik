package com.comet.opik.utils;

import com.comet.opik.domain.SpanType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.text.StringEscapeUtils;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@UtilityClass
@Slf4j
public class OpenTelemetryUtils {
    /**
     * Converts a
     *
     * @param otelSpan
     * @return
     */
    public static com.comet.opik.api.Span toOpikSpan(Span otelSpan) {
        var startTimeMs = otelSpan.getStartTimeUnixNano() / 1_000_000L;
        var endTimeMs = otelSpan.getEndTimeUnixNano() / 1_000_000L;
        var durationMs = (otelSpan.getEndTimeUnixNano() - otelSpan.getStartTimeUnixNano()) / 1_000_000d;

        var otelTraceId = otelSpan.getTraceId();
        var opikTraceId = convertOtelIdToUUIDv7(otelTraceId.toByteArray(), startTimeMs, true);

        var otelSpanId = otelSpan.getSpanId();
        var opikSpanId = convertOtelIdToUUIDv7(otelSpanId.toByteArray(), startTimeMs, true);

        var otelParentSpanId = otelSpan.getParentSpanId();
        var opikParentSpanId = otelParentSpanId.isEmpty()
                ? null
                : convertOtelIdToUUIDv7(otelParentSpanId.toByteArray(), startTimeMs, true);

        var attributes = convertAttributesToJson(otelSpan.getAttributesList());

        return com.comet.opik.api.Span.builder()
                .id(opikSpanId)
                .traceId(opikTraceId)
                .parentSpanId(opikParentSpanId)
                .name(otelSpan.getName())
                .type(SpanType.general)
                .startTime(Instant.ofEpochMilli(startTimeMs))
                .endTime(Instant.ofEpochMilli(endTimeMs))
                .duration(durationMs)
                .createdAt(Instant.now())
                .input(attributes)
                .build();

    }

    /**
     * Converts a list of protobuf KeyValue into a JsonNode, preserving their types.
     *
     * @param attributes a list of
     * @return
     */
    protected static JsonNode convertAttributesToJson(List<KeyValue> attributes) {
        ObjectMapper mapper = JsonUtils.MAPPER;
        ObjectNode node = mapper.createObjectNode();

        // Iterate over each attribute key-value pair
        attributes.forEach(attribute -> {
            var key = attribute.getKey();
            var value = attribute.getValue();

            switch (value.getValueCase()) {
                case STRING_VALUE -> node.put(key, StringEscapeUtils.unescapeJson(value.getStringValue()));
                case INT_VALUE -> node.put(key, value.getIntValue());
                case DOUBLE_VALUE -> node.put(key, value.getDoubleValue());
                case BOOL_VALUE -> node.put(key, value.getBoolValue());
                default -> log.warn("Unsupported attribute: {}", attribute);
            }
        });

        return node;
    }

    static long DAY_MILLISECONDS = 24 * 60 * 60 * 1000L;

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
     * @param spanTimestampMs a timestamp for the span in millis
     * @param timeTruncate truncates the timestamp on returned UUID by a time window level
     * @return a valid UUIDv7
     */
    public static UUID convertOtelIdToUUIDv7(byte[] otelSpanId, long spanTimestampMs, boolean timeTruncate) {
        // Prepare the 16-byte array for the UUID
        byte[] uuidBytes = new byte[16];

        long timestampMs = timeTruncate ? (spanTimestampMs / DAY_MILLISECONDS) * DAY_MILLISECONDS : spanTimestampMs;

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
     * Uses 64-bit integer OpenTelemetry SpanId and its timestamp to prepare a good UUIDv7 id. This is actually
     * a good UUIDv7 (in opposition of the traceId) as its composed from an id and a timestamp, so spans will be
     * properly ordered in the span table.
     *
     * @param otelSpanId a OpenTelemetry 64-bit integer spanId
     * @param timestampMs a timestamp for the span in millis
     * @return a valid UUIDv7
     */
    public static UUID convertOtelIdToUUIDv7(byte[] otelSpanId, long timestampMs) throws Exception {
        return convertOtelIdToUUIDv7(otelSpanId, timestampMs, false);
    }
}
