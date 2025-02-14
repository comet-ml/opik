package com.comet.opik.domain;

import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.ImplementedBy;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringEscapeUtils;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ImplementedBy(OpenTelemetryServiceImpl.class)
public interface OpenTelemetryService {

    Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName,
            @NonNull String userName, @NonNull String workspaceName, @NonNull String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OpenTelemetryServiceImpl implements OpenTelemetryService {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ProjectService projectService;

    @Override
    public Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName,
            @NonNull String userName, @NonNull String workspaceName, @NonNull String workspaceId) {

        //Project project = projectService.getOrCreate(workspaceId, projectName, userName);
        //log.info("Found/Created project {}", project);

        var opikSpans = traceRequest.getResourceSpansList().stream()
                .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                .flatMap(scopeSpans -> scopeSpans.getSpansList().stream())
                .map(otelSpan -> toOpikSpan(otelSpan, projectName, userName))
                .toList();

        // check if theres a span without parentId: we will use it as a Trace
        opikSpans.stream()
                .filter(span -> span.parentSpanId() == null)
                .findFirst()
                .ifPresent(rootSpan -> {
                    var trace = Trace.builder()
                            .id(rootSpan.traceId())
                            .name(rootSpan.name())
                            .projectId(rootSpan.projectId())
                            .startTime(rootSpan.startTime())
                            .endTime(rootSpan.endTime())
                            .duration(rootSpan.duration())
                            .input(rootSpan.input())
                            .output(rootSpan.output())
                            .metadata(rootSpan.metadata())
                            .createdBy(rootSpan.createdBy())
                            .build();

                    var traceIdCreated = traceService.create(trace)
                            .contextWrite(
                                    ctx -> AsyncUtils.setRequestContext(ctx, userName, workspaceName, workspaceId))
                            .block();
                    log.info("Created trace with id: '{}'", traceIdCreated);
                });

        var spanBatch = SpanBatch.builder().spans(opikSpans).build();

        log.info("Parsed OpenTelemetry span batch for project '{}' into {} spans", projectName, opikSpans.size());

        return spanService.create(spanBatch)
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, userName, workspaceName, workspaceId));

    }

    private static com.comet.opik.api.Span toOpikSpan(Span otelSpan, String projectId, String userName) {
        try {
            var startTimeMs = otelSpan.getStartTimeUnixNano() / 1_000_000L;
            var endTimeMs = otelSpan.getEndTimeUnixNano() / 1_000_000L;
            var durationMs = (otelSpan.getEndTimeUnixNano() - otelSpan.getStartTimeUnixNano()) / 1_000_000d;

            var traceId = convertOtelSpanIdToUUIDv7(otelSpan.getTraceId().toByteArray(), startTimeMs, true);
            var spanId = convertOtelSpanIdToUUIDv7(otelSpan.getSpanId().toByteArray(), startTimeMs);
            var parentSpanId = otelSpan.getParentSpanId().isEmpty()
                    ? null
                    : convertOtelSpanIdToUUIDv7(otelSpan.getParentSpanId().toByteArray(), startTimeMs);

            var attributes = convertAttributesToJson(otelSpan.getAttributesList());

            log.info("[traceId: {}] [spanId: {}] [parentSpanId: {}] [name: {}]", traceId, spanId, parentSpanId,
                    otelSpan.getName());

            var opikSpan = com.comet.opik.api.Span.builder()
                    .id(spanId)
                    .traceId(traceId)
                    .parentSpanId(parentSpanId)
                    .name(otelSpan.getName())
                    .type(SpanType.general)
                    .startTime(Instant.ofEpochMilli(startTimeMs))
                    .endTime(Instant.ofEpochMilli(endTimeMs))
                    .duration(durationMs)
                    .projectName(projectId)
                    .createdAt(Instant.now())
                    .createdBy(userName)
                    .input(attributes)
                    .build();

            //            log.info("opik span: {}", opikSpan);

            return opikSpan;
        } catch (Exception e) {
            log.error("Error storing span", e);
        }
        return null;
    }

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

    /**
     * Uses 128-bit integer OpenTelemetry TraceId to prepare a valid UUIDv7. Please notice its just a 'valid' one,
     * as the timestamp part of it'll be filled with nonsense. As libraries like pydantic will send the data into
     * small batches and the 'trace' it's one the LAST one, we need to make sure we can generate a valid trace out
     * of any order of these batches received.
     *
     * @param otelTraceId a OpenTelemetry 128-bit integer traceId
     * @return a valid UUIDv7
     * @throws Exception
     */
    protected static UUID convertOtelTraceIdToUUIDv7(byte[] otelTraceId) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] uuidBytes = digest.digest(otelTraceId);

        return forceUUIDv7Format(uuidBytes);
    }

    static long weekMillis = 7L * 24 * 60 * 60 * 1000; // 604,800,000 milliseconds

    protected static UUID convertOtelSpanIdToUUIDv7(byte[] otelSpanId, long spanTimestampMs, boolean weekTruncate)
            throws Exception {
        // Prepare the 16-byte array for the UUID
        byte[] uuidBytes = new byte[16];

        long timestampMs = weekTruncate ? (spanTimestampMs / weekMillis) * weekMillis : spanTimestampMs;

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
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(otelSpanId);
        System.arraycopy(hash, 0, uuidBytes, 6, 10);

        // Set the version to 7 (stored in the high nibble of byte 6)
        return forceUUIDv7Format(uuidBytes);
    }

    /**
     * Uses 64-bit integer OpenTelemetry SpanId and its timestamp to prepare a good UUIDv7 id. This is actually
     * a good UUIDv7 (in opposition of the traceId) as its composed from an id and a timestamp, so spans will be
     * properly ordered in the span table.
     *
     * @param otelSpanId a OpenTelemetry 64-bit integer spanId
     * @param timestampMs a timestamp for the span in millis
     * @return a valid UUIDv7
     * @throws Exception
     */
    protected static UUID convertOtelSpanIdToUUIDv7(byte[] otelSpanId, long timestampMs) throws Exception {
        return convertOtelSpanIdToUUIDv7(otelSpanId, timestampMs, false);
    }

    /**
     * Enforces the byte array to be a valid UUIDv7, setting the proper flags.
     * If an array larger than 16 bytes is provided, it will be ignored after the 15th-byte.
     *
     * @param uuidBytes a 16-byte array to be converted into an UUIDv7
     * @return a valid UUIDv7
     */
    private static UUID forceUUIDv7Format(byte[] uuidBytes) {
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
}