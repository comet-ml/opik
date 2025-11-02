package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service responsible for enriching trace data with additional metadata
 * such as nested spans, tags, comments, feedback scores, and usage metrics.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class TraceEnrichmentService {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ObjectMapper objectMapper;

    /**
     * Enriches multiple traces with additional metadata based on the provided options.
     *
     * @param traceIds The IDs of the traces to enrich
     * @param options Options specifying which metadata to include
     * @return A Mono containing a map of trace IDs to their enriched data
     */
    @WithSpan
    public Mono<Map<UUID, Map<String, JsonNode>>> enrichTraces(
            @NonNull Set<UUID> traceIds,
            @NonNull TraceEnrichmentOptions options) {

        if (traceIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        log.info("Enriching '{}' traces with options '{}'", traceIds.size(), options);

        // Fetch all traces
        Mono<Map<UUID, Trace>> tracesMono = traceService.getByIds(List.copyOf(traceIds))
                .collectMap(Trace::id);

        // Fetch all spans if needed
        Mono<Map<UUID, List<Span>>> spansMono = options.includeSpans()
                ? spanService.getByTraceIds(traceIds)
                        .collect(Collectors.groupingBy(Span::traceId))
                : Mono.just(Map.of());

        return Mono.zip(tracesMono, spansMono)
                .flatMap(tuple -> {
                    Map<UUID, Trace> traces = tuple.getT1();
                    Map<UUID, List<Span>> spansByTraceId = tuple.getT2();

                    Map<UUID, Map<String, JsonNode>> enrichedTraces = traces.entrySet().stream()
                            .collect(Collectors.toMap(
                                    Map.Entry::getKey,
                                    entry -> enrichTraceData(
                                            entry.getValue(),
                                            spansByTraceId.getOrDefault(entry.getKey(), List.of()),
                                            options)));

                    return Mono.just(enrichedTraces);
                });
    }

    private Map<String, JsonNode> enrichTraceData(
            @NonNull Trace trace,
            @NonNull List<Span> spans,
            @NonNull TraceEnrichmentOptions options) {

        ObjectNode enrichedData = objectMapper.createObjectNode();

        // Always include input and output
        enrichedData.set("input", objectMapper.valueToTree(trace.input()));
        if (trace.output() != null) {
            enrichedData.set("expected_output", objectMapper.valueToTree(trace.output()));
        }

        // Include spans if requested
        if (options.includeSpans() && !spans.isEmpty()) {
            ArrayNode spansArray = objectMapper.createArrayNode();
            for (Span span : spans) {
                ObjectNode spanNode = objectMapper.createObjectNode();
                spanNode.put("id", span.id().toString());
                spanNode.put("name", span.name());
                spanNode.put("type", span.type().toString());
                if (span.parentSpanId() != null) {
                    spanNode.put("parent_span_id", span.parentSpanId().toString());
                }
                spanNode.set("input", objectMapper.valueToTree(span.input()));
                if (span.output() != null) {
                    spanNode.set("output", objectMapper.valueToTree(span.output()));
                }
                if (span.startTime() != null) {
                    spanNode.put("start_time", span.startTime().toString());
                }
                if (span.endTime() != null) {
                    spanNode.put("end_time", span.endTime().toString());
                }
                if (span.metadata() != null) {
                    spanNode.set("metadata", objectMapper.valueToTree(span.metadata()));
                }
                if (span.feedbackScores() != null && !span.feedbackScores().isEmpty()) {
                    spanNode.set("feedback_scores", objectMapper.valueToTree(span.feedbackScores()));
                }
                if (span.comments() != null && !span.comments().isEmpty()) {
                    spanNode.set("comments", objectMapper.valueToTree(span.comments()));
                }
                spansArray.add(spanNode);
            }
            enrichedData.set("spans", spansArray);
        }

        // Include tags if requested
        if (options.includeTags() && trace.tags() != null && !trace.tags().isEmpty()) {
            enrichedData.set("tags", objectMapper.valueToTree(trace.tags()));
        }

        // Include feedback scores if requested
        if (options.includeFeedbackScores() && trace.feedbackScores() != null
                && !trace.feedbackScores().isEmpty()) {
            enrichedData.set("feedback_scores", objectMapper.valueToTree(trace.feedbackScores()));
        }

        // Include comments if requested
        if (options.includeComments() && trace.comments() != null && !trace.comments().isEmpty()) {
            enrichedData.set("comments", objectMapper.valueToTree(trace.comments()));
        }

        // Include usage if requested
        if (options.includeUsage() && trace.usage() != null && !trace.usage().isEmpty()) {
            enrichedData.set("usage", objectMapper.valueToTree(trace.usage()));
        }

        // Include metadata if requested
        if (options.includeMetadata() && trace.metadata() != null) {
            enrichedData.set("metadata", objectMapper.valueToTree(trace.metadata()));
        }

        @SuppressWarnings("unchecked")
        Map<String, JsonNode> result = objectMapper.convertValue(enrichedData, Map.class);
        return result;
    }

    /**
     * Options for trace enrichment specifying which metadata to include.
     */
    @Builder(toBuilder = true)
    public record TraceEnrichmentOptions(
            boolean includeSpans,
            boolean includeTags,
            boolean includeFeedbackScores,
            boolean includeComments,
            boolean includeUsage,
            boolean includeMetadata) {

        public static TraceEnrichmentOptions all() {
            return new TraceEnrichmentOptions(true, true, true, true, true, true);
        }

        public static TraceEnrichmentOptions none() {
            return new TraceEnrichmentOptions(false, false, false, false, false, false);
        }
    }
}
