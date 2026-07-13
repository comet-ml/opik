package com.comet.opik.domain;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span.SpanBuilder;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRuleFactory;
import com.comet.opik.domain.mapping.otel.ElasticInferenceServiceResolver;
import com.comet.opik.domain.mapping.otel.GeneralMappingRules;
import com.comet.opik.domain.mapping.otel.GoogleProviderResolver;
import com.comet.opik.domain.retention.RetentionUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
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
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractCost;
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

        // Check for opik.trace_id, opik.span_id, and opik.parent_span_id override attributes.
        // When present, these connect the span to an existing OPIK trace/span as-is (no ID conversion).
        // opik.span_id is typically set by the SDK's OpikSpanProcessor, which mints a UUIDv7 per span and
        // chains it via opik.parent_span_id so descendants of an attached subtree stay properly linked.
        var opikSpanIdOverride = extractOpikSpanId(otelSpan);
        var opikTraceIdOverride = extractOpikTraceId(otelSpan);
        var opikParentSpanIdOverride = extractOpikParentSpanId(otelSpan);

        var opikSpanId = opikSpanIdOverride
                .orElseGet(() -> convertOtelIdToUUIDv7(otelSpanId.toByteArray(), traceTimestamp));

        UUID effectiveTraceId;
        UUID opikParentSpanId;

        if (opikTraceIdOverride.isPresent()) {
            effectiveTraceId = opikTraceIdOverride.get();
            // When opik.trace_id is set, use opik.parent_span_id if available, otherwise null
            // (span connects directly to the trace as a root span)
            opikParentSpanId = opikParentSpanIdOverride.orElse(null);
        } else {
            if (opikParentSpanIdOverride.isPresent()) {
                log.warn("Span '{}' has '{}' without '{}', ignoring parent span ID override",
                        otelSpan.getName(), GeneralMappingRules.OPIK_PARENT_SPAN_ID_ATTR,
                        GeneralMappingRules.OPIK_TRACE_ID_ATTR);
            }
            effectiveTraceId = opikTraceId;
            var otelParentSpanId = otelSpan.getParentSpanId();
            opikParentSpanId = otelParentSpanId.isEmpty()
                    ? null
                    : convertOtelIdToUUIDv7(otelParentSpanId.toByteArray(), traceTimestamp);
        }

        var spanBuilder = com.comet.opik.api.Span.builder()
                .id(opikSpanId)
                .traceId(effectiveTraceId)
                .parentSpanId(opikParentSpanId)
                .name(otelSpan.getName())
                .type(SpanType.general)
                .source(Source.SDK)
                .startTime(Instant.ofEpochMilli(startTimeMs))
                .endTime(Instant.ofEpochMilli(endTimeMs));

        List<Span.Event> events = otelSpan.getEventsList();
        enrichSpanWithAttributes(spanBuilder, otelSpan.getAttributesList(), integrationName, events,
                otelSpan.getName());

        extractErrorInfo(otelSpan).ifPresent(spanBuilder::errorInfo);

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
        enrichSpanWithAttributes(spanBuilder, attributes, integrationName, events, null);
    }

    private static final String CLAUDE_CODE_LLM_SPAN = "claude_code.llm_request";
    private static final String NEW_CONTEXT_ATTR = "new_context";

    /**
     * Same as {@link #enrichSpanWithAttributes(SpanBuilder, List, String, List)} but with the OTEL
     * span name, used for span-name-aware routing (e.g. Claude Code's {@code new_context} maps to
     * input only on {@code claude_code.llm_request} spans).
     *
     * @param spanName the OTEL span name (may be null)
     */
    public static void enrichSpanWithAttributes(SpanBuilder spanBuilder, List<KeyValue> attributes,
            String integrationName, List<Span.Event> events, String spanName) {
        Map<String, Integer> usage = new HashMap<>();
        ObjectNode input = JsonUtils.createObjectNode();
        ObjectNode output = JsonUtils.createObjectNode();
        ObjectNode metadata = JsonUtils.createObjectNode();
        Set<String> tags = new HashSet<>();
        // Claude Code spans carry a lot of session/config attributes that aren't input. For that
        // integration the default bucket for unmapped attributes is metadata (not input), so only
        // the explicitly promoted content attributes land in input/output/usage.
        // Decided per span by name (not from the batch-level integrationName below): a single OTLP
        // batch can mix scopes from more than one integration, so gating this on the batch-wide
        // value could misroute a non-Claude span or skip routing for a real Claude Code span.
        boolean isClaudeCode = OpenTelemetryMappingRuleFactory.isClaudeCodeSpan(spanName);
        ObjectNode defaultBucket = isClaudeCode ? metadata : input;

        // Hold model and provider until the attribute loop completes so we can apply
        // post-processing (e.g. Elastic Inference Service routing) that needs both values.
        // Claude Code is Anthropic-only and never sends a provider attribute, so set it directly.
        String model = null;
        String provider = isClaudeCode ? "anthropic" : null;

        if (StringUtils.isNotBlank(integrationName)) {
            metadata.put("integration", integrationName);
        }

        // Iterate over each attribute key-value pair
        for (KeyValue attribute : attributes) {
            var key = attribute.getKey();
            var value = attribute.getValue();

            // Claude Code's `new_context` is the latest message fed to the model on llm_request
            // spans (the real LLM input); on interaction/tool spans it just repeats the prompt /
            // tool result, so it's kept in metadata there rather than input.
            if (isClaudeCode && NEW_CONTEXT_ATTR.equals(key)) {
                extractToJsonColumn(CLAUDE_CODE_LLM_SPAN.equals(spanName) ? input : metadata, key, value);
                continue;
            }

            var ruleOpt = OpenTelemetryMappingRuleFactory.findRule(key, isClaudeCode);

            if (ruleOpt.isEmpty()) {
                log.debug("No rule found for unmapped attribute key '{}'. Using default bucket.", key);
                extractToJsonColumn(defaultBucket, key, value);
                continue;
            }

            var rule = ruleOpt.get();
            Optional.ofNullable(rule.getSpanType()).ifPresent(spanBuilder::type);

            switch (rule.getOutcome()) {
                case MODEL :
                    model = value.getStringValue();
                    break;

                case PROVIDER :
                    provider = value.getStringValue();
                    break;

                case USAGE :
                    extractUsageField(usage, rule, key, value);
                    break;

                case COST :
                    extractCost(value).ifPresent(spanBuilder::totalEstimatedCost);
                    break;

                case INPUT :
                case OUTPUT :
                case METADATA :
                    ObjectNode node = switch (rule.getOutcome()) {
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
        }

        // Process events and add them to metadata
        processEvents(events, metadata);

        // Claude Code emits the tool result as a `tool.output` span event (Bash carries it on
        // `output`, file tools on `content`). Surface it as the tool span's output instead of
        // leaving it only in metadata.opentelemetry.events.
        if (isClaudeCode) {
            extractToolOutputEvent(events, output);
        }

        // Rewrite Elastic Inference Service model/provider into the underlying provider so
        // that cost lookup and provider-based filtering see the real upstream. Records the
        // original values in metadata for traceability. Returns the (possibly unchanged) pair.
        var resolved = ElasticInferenceServiceResolver.resolve(model, provider, metadata);
        model = resolved.model();
        provider = resolved.provider();

        // Disambiguate the generic 'google' provider (PydanticAI / google-genai) into the Vertex AI
        // vs Gemini API canonical name using server.address, so cost lookup can match a price row.
        provider = GoogleProviderResolver.resolve(provider, metadata);

        // Agent-run spans (gen_ai.operation.name=invoke_agent) are not LLM calls. Other attributes
        // on them (e.g. gen_ai.system_instructions) would otherwise type them as llm; force general.
        if ("invoke_agent".equals(metadata.path("gen_ai.operation.name").asText(null))) {
            spanBuilder.type(SpanType.general);
        }

        if (model != null) {
            spanBuilder.model(model);
        }
        if (provider != null) {
            spanBuilder.provider(provider);
        }

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
            // Some integrations (e.g. PydanticAI) send prompt_tokens and completion_tokens
            // but omit total_tokens. Compute it so callers always get a complete picture.
            if (!usage.containsKey("total_tokens")
                    && usage.containsKey("prompt_tokens")
                    && usage.containsKey("completion_tokens")) {
                usage.put("total_tokens", usage.get("prompt_tokens") + usage.get("completion_tokens"));
            }
            spanBuilder.usage(usage);
        }
        if (!tags.isEmpty()) {
            spanBuilder.tags(tags);
        }
    }

    private static final String TOOL_OUTPUT_EVENT_NAME = "tool.output";
    private static final Set<String> TOOL_OUTPUT_CONTENT_KEYS = Set.of("output", "content");

    /**
     * Maps a Claude Code {@code tool.output} span event into the tool span's output. The event
     * carries the tool result on {@code output} (Bash) or {@code content} (file tools). The last
     * event wins if several are present.
     *
     * @param events the list of events extracted from the otel payload
     * @param output the output node to populate
     */
    private static void extractToolOutputEvent(List<Span.Event> events, ObjectNode output) {
        findLastEvent(events, TOOL_OUTPUT_EVENT_NAME)
                .ifPresent(event -> event.getAttributesList().stream()
                        .filter(attribute -> TOOL_OUTPUT_CONTENT_KEYS.contains(attribute.getKey()))
                        .forEach(attribute -> extractToJsonColumn(output, attribute.getKey(), attribute.getValue())));
    }

    /**
     * Finds the last span event with the given name; the last one wins when several exist.
     */
    private static Optional<Span.Event> findLastEvent(List<Span.Event> events, String name) {
        if (CollectionUtils.isEmpty(events)) {
            return Optional.empty();
        }
        return events.stream()
                .filter(event -> name.equals(event.getName()))
                .reduce((first, second) -> second);
    }

    private static final String EXCEPTION_EVENT_NAME = "exception";
    private static final String EXCEPTION_TYPE_ATTR = "exception.type";
    private static final String EXCEPTION_MESSAGE_ATTR = "exception.message";
    private static final String EXCEPTION_STACKTRACE_ATTR = "exception.stacktrace";
    private static final String DEFAULT_EXCEPTION_TYPE = "Error";

    /**
     * Translates the OpenTelemetry error signals into Opik's {@link ErrorInfo}, so failed spans
     * surface as errors instead of hiding the failure inside raw event metadata. Both signals are
     * OTel core conventions emitted by every instrumentation, not PydanticAI-specific:
     * <ul>
     *     <li>An {@code exception} span event (from {@code Span.record_exception}) carrying
     *     {@code exception.type} / {@code exception.message} / {@code exception.stacktrace}.</li>
     *     <li>A span {@code STATUS_CODE_ERROR} status with an optional message.</li>
     * </ul>
     * The exception event is richer, so it takes precedence; the last one wins when several exist.
     *
     * @param otelSpan the OpenTelemetry span to inspect
     * @return the extracted error info, or empty when the span did not fail
     */
    static Optional<ErrorInfo> extractErrorInfo(Span otelSpan) {
        var exceptionEvent = findLastEvent(otelSpan.getEventsList(), EXCEPTION_EVENT_NAME);

        if (exceptionEvent.isPresent()) {
            var attributes = exceptionEvent.get().getAttributesList();
            var message = eventAttribute(attributes, EXCEPTION_MESSAGE_ATTR);
            return Optional.of(ErrorInfo.builder()
                    .exceptionType(StringUtils.firstNonBlank(
                            eventAttribute(attributes, EXCEPTION_TYPE_ATTR), DEFAULT_EXCEPTION_TYPE))
                    .message(message)
                    .traceback(StringUtils.firstNonBlank(
                            eventAttribute(attributes, EXCEPTION_STACKTRACE_ATTR), message, DEFAULT_EXCEPTION_TYPE))
                    .build());
        }

        if (otelSpan.getStatus().getCode() == Status.StatusCode.STATUS_CODE_ERROR) {
            var message = StringUtils.trimToNull(otelSpan.getStatus().getMessage());
            return Optional.of(ErrorInfo.builder()
                    .exceptionType(DEFAULT_EXCEPTION_TYPE)
                    .message(message)
                    .traceback(StringUtils.firstNonBlank(message, DEFAULT_EXCEPTION_TYPE))
                    .build());
        }

        return Optional.empty();
    }

    private static String eventAttribute(List<KeyValue> attributes, String key) {
        return attributes.stream()
                .filter(attribute -> key.equals(attribute.getKey()))
                .map(attribute -> attribute.getValue().getStringValue())
                .findFirst()
                .orElse(null);
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
        return RetentionUtils.extractInstant(uuid).toEpochMilli();
    }

    /**
     * Extracts the opik.trace_id attribute from an OTEL span, if present.
     * This attribute allows connecting an OTEL span to an existing OPIK trace.
     *
     * @param otelSpan the OTEL span to extract from
     * @return the OPIK trace UUID if the attribute is present and valid
     */
    public static Optional<UUID> extractOpikTraceId(Span otelSpan) {
        return extractStringAttribute(otelSpan, GeneralMappingRules.OPIK_TRACE_ID_ATTR)
                .flatMap(value -> parseUUIDv7(value, GeneralMappingRules.OPIK_TRACE_ID_ATTR));
    }

    /**
     * Extracts the opik.parent_span_id attribute from an OTEL span, if present.
     * This attribute allows connecting an OTEL span to an existing OPIK span as its parent.
     * Only meaningful when opik.trace_id is also present.
     *
     * @param otelSpan the OTEL span to extract from
     * @return the OPIK parent span UUID if the attribute is present and valid
     */
    public static Optional<UUID> extractOpikParentSpanId(Span otelSpan) {
        return extractStringAttribute(otelSpan, GeneralMappingRules.OPIK_PARENT_SPAN_ID_ATTR)
                .flatMap(value -> parseUUIDv7(value, GeneralMappingRules.OPIK_PARENT_SPAN_ID_ATTR));
    }

    /**
     * Extracts the opik.span_id attribute from an OTEL span, if present.
     * When set, the value is used verbatim as the Opik span id, bypassing the SHA-256
     * conversion of the OTEL span id. The SDK's OpikSpanProcessor mints this per span
     * and threads it as opik.parent_span_id on each child, so descendants of an attached
     * OTEL subtree stay linked across batch boundaries without relying on Redis.
     *
     * @param otelSpan the OTEL span to extract from
     * @return the OPIK span UUID if the attribute is present and valid
     */
    public static Optional<UUID> extractOpikSpanId(Span otelSpan) {
        return extractStringAttribute(otelSpan, GeneralMappingRules.OPIK_SPAN_ID_ATTR)
                .flatMap(value -> parseUUIDv7(value, GeneralMappingRules.OPIK_SPAN_ID_ATTR));
    }

    private static Optional<String> extractStringAttribute(Span otelSpan, String key) {
        return otelSpan.getAttributesList().stream()
                .filter(attr -> key.equals(attr.getKey()))
                .map(attr -> attr.getValue().getStringValue())
                .filter(StringUtils::isNotBlank)
                .findFirst();
    }

    private static Optional<UUID> parseUUIDv7(String value, String attributeName) {
        try {
            var uuid = UUID.fromString(value);
            if (uuid.version() != 7) {
                log.warn("Attribute '{}' value '{}' is not a UUIDv7 (version {}), ignoring",
                        attributeName, value, uuid.version());
                return Optional.empty();
            }
            return Optional.of(uuid);
        } catch (IllegalArgumentException e) {
            log.warn("Attribute '{}' value '{}' is not a valid UUIDv7, ignoring", attributeName, value);
            return Optional.empty();
        }
    }
}
