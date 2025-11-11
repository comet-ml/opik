package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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
        Mono<List<Trace>> tracesMono = traceService.getByIds(List.copyOf(traceIds))
                .collectList();

        // Fetch all spans if needed
        Mono<Map<UUID, List<Span>>> spansMono = options.includeSpans()
                ? spanService.getByTraceIds(traceIds)
                        .collect(Collectors.groupingBy(Span::traceId))
                : Mono.just(Map.of());

        return Mono.zip(tracesMono, spansMono)
                .flatMap(tuple -> {
                    List<Trace> traces = tuple.getT1();
                    Map<UUID, List<Span>> spansByTraceId = tuple.getT2();

                    Map<UUID, Map<String, JsonNode>> enrichedTraces = traces.stream()
                            .collect(Collectors.toMap(
                                    Trace::id,
                                    trace -> TraceEnrichmentMapper.enrichTraceData(
                                            trace,
                                            spansByTraceId.getOrDefault(trace.id(), List.of()),
                                            options)));

                    return Mono.just(enrichedTraces);
                });
    }
}
