package com.comet.opik.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service responsible for enriching span data with additional metadata
 * such as tags, comments, feedback scores, and usage metrics.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class SpanEnrichmentService {

    private final @NonNull SpanService spanService;

    /**
     * Enriches multiple spans with additional metadata based on the provided options.
     *
     * @param spanIds The IDs of the spans to enrich
     * @param options Options specifying which metadata to include
     * @return A Mono containing a map of span IDs to their enriched data
     */
    @WithSpan
    public Mono<Map<UUID, Map<String, JsonNode>>> enrichSpans(
            @NonNull Set<UUID> spanIds,
            @NonNull SpanEnrichmentOptions options) {

        if (spanIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        log.info("Enriching '{}' spans with options '{}'", spanIds.size(), options);

        // Fetch all spans and enrich them reactively
        return spanService.getByIds(spanIds)
                .map(span -> Map.entry(span.id(), SpanEnrichmentMapper.enrichSpanData(span, options)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }
}
