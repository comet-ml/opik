package com.comet.opik.domain;

import com.comet.opik.api.Comment;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Span;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpanEnrichmentServiceTest {

    private SpanEnrichmentService spanEnrichmentService;

    @Mock
    private SpanService spanService;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @BeforeEach
    void setUp() {
        spanEnrichmentService = new SpanEnrichmentService(spanService);
    }

    private void assertMapKeysPresence(Map<String, JsonNode> data, Set<String> expectedKeys,
            Set<String> unexpectedKeys) {
        expectedKeys.forEach(key -> assertThat(data).containsKey(key));
        unexpectedKeys.forEach(key -> assertThat(data).doesNotContainKey(key));
    }

    @Nested
    @DisplayName("Enrich Spans:")
    class EnrichSpans {

        @Test
        @DisplayName("when span IDs are empty, then return empty map")
        void enrichSpans__whenSpanIdsAreEmpty__thenReturnEmptyMap() {
            // given
            Set<UUID> spanIds = Set.of();
            var options = SpanEnrichmentOptions.builder().build();

            // when
            Map<UUID, Map<String, JsonNode>> result = spanEnrichmentService.enrichSpans(spanIds, options).block();

            // then
            assertThat(result).isEmpty();
            verify(spanService, never()).getByIds(any());
        }

        @Test
        @DisplayName("when enriching with no options, then return only input and output")
        void enrichSpans__whenEnrichingWithNoOptions__thenReturnOnlyInputAndOutput() {
            // given
            UUID spanId = UUID.randomUUID();
            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(spanId)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                    .build();

            when(spanService.getByIds(Set.of(spanId))).thenReturn(Flux.just(span));

            var options = SpanEnrichmentOptions.builder().build();

            // when
            Map<UUID, Map<String, JsonNode>> result = spanEnrichmentService.enrichSpans(Set.of(spanId), options)
                    .block();

            // then
            assertThat(result).hasSize(1);
            assertThat(result).containsKey(spanId);

            Map<String, JsonNode> enrichedData = result.get(spanId);
            assertMapKeysPresence(enrichedData,
                    Set.of("input", "expected_output"),
                    Set.of("tags", "feedback_scores", "comments", "usage", "metadata"));
        }

        @Test
        @DisplayName("when enriching with all options, then include all metadata")
        void enrichSpans__whenEnrichingWithAllOptions__thenIncludeAllMetadata() {
            // given
            UUID spanId = UUID.randomUUID();

            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(spanId)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                    .tags(Set.of("tag1", "tag2"))
                    .feedbackScores(List.of(
                            podamFactory.manufacturePojo(FeedbackScore.class)))
                    .comments(List.of(
                            podamFactory.manufacturePojo(Comment.class)))
                    .usage(Map.of("tokens", 100))
                    .metadata(JsonUtils.getJsonNodeFromString("{\"key\": \"value\"}"))
                    .build();

            when(spanService.getByIds(Set.of(spanId))).thenReturn(Flux.just(span));

            var options = SpanEnrichmentOptions.builder()
                    .includeTags(true)
                    .includeFeedbackScores(true)
                    .includeComments(true)
                    .includeUsage(true)
                    .includeMetadata(true)
                    .build();

            // when
            Map<UUID, Map<String, JsonNode>> result = spanEnrichmentService.enrichSpans(Set.of(spanId), options)
                    .block();

            // then
            assertThat(result).hasSize(1);
            Map<String, JsonNode> enrichedData = result.get(spanId);

            assertMapKeysPresence(enrichedData,
                    Set.of("input", "expected_output", "tags", "feedback_scores", "comments", "usage", "metadata"),
                    Set.of());
        }

        @Test
        @DisplayName("when enriching multiple spans, then return all enriched spans")
        void enrichSpans__whenEnrichingMultipleSpans__thenReturnAllEnrichedSpans() {
            // given
            UUID spanId1 = UUID.randomUUID();
            UUID spanId2 = UUID.randomUUID();

            Span span1 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(spanId1)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 1\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 1\"}"))
                    .build();

            Span span2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(spanId2)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 2\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 2\"}"))
                    .build();

            when(spanService.getByIds(Set.of(spanId1, spanId2))).thenReturn(Flux.just(span1, span2));

            var options = SpanEnrichmentOptions.builder().build();

            // when
            Map<UUID, Map<String, JsonNode>> result = spanEnrichmentService
                    .enrichSpans(Set.of(spanId1, spanId2), options).block();

            // then
            assertThat(result).hasSize(2);
            assertThat(result).containsKeys(spanId1, spanId2);
        }

        @Test
        @DisplayName("when enriching with tags only, then include only tags")
        void enrichSpans__whenEnrichingWithTagsOnly__thenIncludeOnlyTags() {
            // given
            UUID spanId = UUID.randomUUID();

            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(spanId)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                    .tags(Set.of("tag1", "tag2"))
                    .feedbackScores(List.of(podamFactory.manufacturePojo(FeedbackScore.class)))
                    .metadata(JsonUtils.getJsonNodeFromString("{\"key\": \"value\"}"))
                    .build();

            when(spanService.getByIds(Set.of(spanId))).thenReturn(Flux.just(span));

            var options = SpanEnrichmentOptions.builder()
                    .includeTags(true)
                    .build();

            // when
            Map<UUID, Map<String, JsonNode>> result = spanEnrichmentService.enrichSpans(Set.of(spanId), options)
                    .block();

            // then
            assertThat(result).hasSize(1);
            Map<String, JsonNode> enrichedData = result.get(spanId);

            assertMapKeysPresence(enrichedData,
                    Set.of("input", "expected_output", "tags"),
                    Set.of("feedback_scores", "metadata"));
        }

        @Test
        @DisplayName("when span has no optional data, then include only input and output")
        void enrichSpans__whenSpanHasNoOptionalData__thenIncludeOnlyInputAndOutput() {
            // given
            UUID spanId = UUID.randomUUID();

            Span span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .id(spanId)
                    .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                    .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                    .tags(null)
                    .feedbackScores(null)
                    .comments(null)
                    .usage(null)
                    .metadata(null)
                    .build();

            when(spanService.getByIds(Set.of(spanId))).thenReturn(Flux.just(span));

            var options = SpanEnrichmentOptions.builder()
                    .includeTags(true)
                    .includeFeedbackScores(true)
                    .includeComments(true)
                    .includeUsage(true)
                    .includeMetadata(true)
                    .build();

            // when
            Map<UUID, Map<String, JsonNode>> result = spanEnrichmentService.enrichSpans(Set.of(spanId), options)
                    .block();

            // then
            assertThat(result).hasSize(1);
            Map<String, JsonNode> enrichedData = result.get(spanId);

            // Only input and output should be present
            assertMapKeysPresence(enrichedData,
                    Set.of("input", "expected_output"),
                    Set.of("tags", "feedback_scores", "comments", "usage", "metadata"));
        }
    }
}
