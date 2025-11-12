package com.comet.opik.domain;

import com.comet.opik.api.Comment;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraceEnrichmentServiceTest {

    private TraceEnrichmentService traceEnrichmentService;

    @Mock
    private TraceService traceService;

    @Mock
    private SpanService spanService;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @BeforeEach
    void setUp() {
        traceEnrichmentService = new TraceEnrichmentService(
                traceService,
                spanService);
    }

    @Nested
    @DisplayName("Enrich Traces:")
    class EnrichTraces {

        @Test
        @DisplayName("when trace IDs are empty, then return empty map")
        void enrichTraces__whenTraceIdsAreEmpty__thenReturnEmptyMap() {
            // given
            Set<UUID> traceIds = Set.of();
            var options = TraceEnrichmentOptions.builder().build();

            // when
            Map<UUID, Map<String, JsonNode>> result = traceEnrichmentService.enrichTraces(traceIds, options).block();

            // then
            assertThat(result).isEmpty();
            verify(traceService, never()).getByIds(any());
            verify(spanService, never()).getByTraceIds(any());
        }

        @Test
        @DisplayName("when enriching with no options, then return only input and output")
        void enrichTraces__whenEnrichingWithNoOptions__thenReturnOnlyInputAndOutput() {
            // given
            UUID traceId = UUID.randomUUID();
            Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(traceId)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                    .build();

            when(traceService.getByIds(List.of(traceId))).thenReturn(Flux.just(trace));
            lenient().when(spanService.getByTraceIds(any())).thenReturn(Flux.empty());

            var options = TraceEnrichmentOptions.builder().build();

            // when
            Map<UUID, Map<String, JsonNode>> result = traceEnrichmentService.enrichTraces(Set.of(traceId), options)
                    .block();

            // then
            assertThat(result).hasSize(1);
            assertThat(result).containsKey(traceId);

            Map<String, JsonNode> enrichedData = result.get(traceId);
            assertThat(enrichedData).containsKey("input");
            assertThat(enrichedData).containsKey("expected_output");
            assertThat(enrichedData).doesNotContainKey("spans");
            assertThat(enrichedData).doesNotContainKey("tags");
            assertThat(enrichedData).doesNotContainKey("feedback_scores");
            assertThat(enrichedData).doesNotContainKey("comments");
            assertThat(enrichedData).doesNotContainKey("usage");
            assertThat(enrichedData).doesNotContainKey("metadata");

            verify(spanService, never()).getByTraceIds(any());
        }

        @Test
        @DisplayName("when enriching with spans, then include spans in result")
        void enrichTraces__whenEnrichingWithSpans__thenIncludeSpansInResult() {
            // given
            UUID traceId = UUID.randomUUID();
            UUID spanId1 = UUID.randomUUID();
            UUID spanId2 = UUID.randomUUID();

            Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(traceId)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                    .build();

            Span span1 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(spanId1)
                    .traceId(traceId)
                    .name("span1")
                    .input(JsonUtils.getJsonNodeFromString("{\"input\": \"span1 input\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"span1 output\"}"))
                    .build();

            Span span2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(spanId2)
                    .traceId(traceId)
                    .name("span2")
                    .parentSpanId(spanId1)
                    .input(JsonUtils.getJsonNodeFromString("{\"input\": \"span2 input\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"output\": \"span2 output\"}"))
                    .build();

            when(traceService.getByIds(List.of(traceId))).thenReturn(Flux.just(trace));
            when(spanService.getByTraceIds(Set.of(traceId))).thenReturn(Flux.just(span1, span2));

            var options = TraceEnrichmentOptions.builder()
                    .includeSpans(true)
                    .build();

            // when
            Map<UUID, Map<String, JsonNode>> result = traceEnrichmentService.enrichTraces(Set.of(traceId), options)
                    .block();

            // then
            assertThat(result).hasSize(1);
            Map<String, JsonNode> enrichedData = result.get(traceId);

            assertThat(enrichedData).containsKey("spans");
            JsonNode spansNode = enrichedData.get("spans");
            assertThat(spansNode.isArray()).isTrue();
            assertThat(spansNode.size()).isEqualTo(2);

            verify(spanService).getByTraceIds(Set.of(traceId));
        }

        @Test
        @DisplayName("when enriching with all options, then include all metadata")
        void enrichTraces__whenEnrichingWithAllOptions__thenIncludeAllMetadata() {
            // given
            UUID traceId = UUID.randomUUID();

            Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(traceId)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                    .tags(Set.of("tag1", "tag2"))
                    .feedbackScores(List.of(
                            podamFactory.manufacturePojo(FeedbackScore.class)))
                    .comments(List.of(
                            podamFactory.manufacturePojo(Comment.class)))
                    .usage(Map.of("tokens", 100L))
                    .metadata(JsonUtils.getJsonNodeFromString("{\"key\": \"value\"}"))
                    .build();

            when(traceService.getByIds(List.of(traceId))).thenReturn(Flux.just(trace));
            when(spanService.getByTraceIds(Set.of(traceId))).thenReturn(Flux.empty());

            var options = TraceEnrichmentOptions.builder()
                    .includeSpans(true)
                    .includeTags(true)
                    .includeFeedbackScores(true)
                    .includeComments(true)
                    .includeUsage(true)
                    .includeMetadata(true)
                    .build();

            // when
            Map<UUID, Map<String, JsonNode>> result = traceEnrichmentService.enrichTraces(Set.of(traceId), options)
                    .block();

            // then
            assertThat(result).hasSize(1);
            Map<String, JsonNode> enrichedData = result.get(traceId);

            assertThat(enrichedData).containsKey("input");
            assertThat(enrichedData).containsKey("expected_output");
            assertThat(enrichedData).containsKey("tags");
            assertThat(enrichedData).containsKey("feedback_scores");
            assertThat(enrichedData).containsKey("comments");
            assertThat(enrichedData).containsKey("usage");
            assertThat(enrichedData).containsKey("metadata");
        }

        @Test
        @DisplayName("when enriching multiple traces, then return all enriched traces")
        void enrichTraces__whenEnrichingMultipleTraces__thenReturnAllEnrichedTraces() {
            // given
            UUID traceId1 = UUID.randomUUID();
            UUID traceId2 = UUID.randomUUID();

            Trace trace1 = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(traceId1)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 1\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 1\"}"))
                    .build();

            Trace trace2 = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(traceId2)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 2\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 2\"}"))
                    .build();

            when(traceService.getByIds(any())).thenReturn(Flux.just(trace1, trace2));
            lenient().when(spanService.getByTraceIds(any())).thenReturn(Flux.empty());

            var options = TraceEnrichmentOptions.builder().build();

            // when
            Map<UUID, Map<String, JsonNode>> result = traceEnrichmentService
                    .enrichTraces(Set.of(traceId1, traceId2), options).block();

            // then
            assertThat(result).hasSize(2);
            assertThat(result).containsKeys(traceId1, traceId2);
        }

        @Test
        @DisplayName("when span has feedback scores and comments, then always include them")
        void enrichTraces__whenSpanHasFeedbackScoresAndComments__thenAlwaysIncludeThem() {
            // given
            UUID traceId = UUID.randomUUID();
            UUID spanId = UUID.randomUUID();

            Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .id(traceId)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                    .build();

            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(spanId)
                    .traceId(traceId)
                    .name("test span")
                    .feedbackScores(List.of(podamFactory.manufacturePojo(FeedbackScore.class)))
                    .comments(List.of(podamFactory.manufacturePojo(Comment.class)))
                    .build();

            when(traceService.getByIds(List.of(traceId))).thenReturn(Flux.just(trace));
            when(spanService.getByTraceIds(Set.of(traceId))).thenReturn(Flux.just(span));

            // Note: includeSpans is true, but includeFeedbackScores and includeComments are false for trace
            var options = TraceEnrichmentOptions.builder()
                    .includeSpans(true)
                    .build();

            // when
            Map<UUID, Map<String, JsonNode>> result = traceEnrichmentService.enrichTraces(Set.of(traceId), options)
                    .block();

            // then
            assertThat(result).hasSize(1);
            Map<String, JsonNode> enrichedData = result.get(traceId);

            assertThat(enrichedData).containsKey("spans");
            JsonNode spansNode = enrichedData.get("spans");
            assertThat(spansNode.isArray()).isTrue();
            assertThat(spansNode.size()).isEqualTo(1);

            JsonNode spanNode = spansNode.get(0);
            // Span should always include feedback scores and comments when present
            assertThat(spanNode.has("feedback_scores")).isTrue();
            assertThat(spanNode.has("comments")).isTrue();
        }
    }
}
