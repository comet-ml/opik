package com.comet.opik.domain;

import com.comet.opik.api.Comment;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Span;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

        // Fetch all spans
        return spanService.getByIds(spanIds)
                .collectList()
                .flatMap(spans -> {
                    Map<UUID, Map<String, JsonNode>> enrichedSpans = spans.stream()
                            .collect(Collectors.toMap(
                                    Span::id,
                                    span -> enrichSpanData(span, options)));

                    return Mono.just(enrichedSpans);
                });
    }

    private Map<String, JsonNode> enrichSpanData(
            @NonNull Span span,
            @NonNull SpanEnrichmentOptions options) {

        ObjectNode enrichedData = JsonUtils.getMapper().createObjectNode();

        // Always include input and output
        enrichedData.set("input", JsonUtils.getMapper().valueToTree(span.input()));
        Optional.ofNullable(span.output())
                .ifPresent(output -> enrichedData.set("expected_output", JsonUtils.getMapper().valueToTree(output)));

        // Include tags if requested
        if (options.includeTags()) {
            Optional.ofNullable(span.tags())
                    .filter(tags -> !tags.isEmpty())
                    .ifPresent(tags -> enrichedData.set("tags", JsonUtils.getMapper().valueToTree(tags)));
        }

        // Include feedback scores if requested
        if (options.includeFeedbackScores()) {
            Optional.ofNullable(span.feedbackScores())
                    .filter(scores -> !scores.isEmpty())
                    .ifPresent(scores -> enrichedData.set("feedback_scores", buildFeedbackScoresNode(scores)));
        }

        // Include comments if requested
        if (options.includeComments()) {
            Optional.ofNullable(span.comments())
                    .filter(comments -> !comments.isEmpty())
                    .ifPresent(comments -> enrichedData.set("comments", buildCommentsNode(comments)));
        }

        // Include usage if requested
        if (options.includeUsage()) {
            Optional.ofNullable(span.usage())
                    .filter(usage -> !usage.isEmpty())
                    .ifPresent(usage -> enrichedData.set("usage", JsonUtils.getMapper().valueToTree(usage)));
        }

        // Include metadata if requested
        if (options.includeMetadata()) {
            Optional.ofNullable(span.metadata())
                    .ifPresent(metadata -> enrichedData.set("metadata", JsonUtils.getMapper().valueToTree(metadata)));
        }

        // Convert ObjectNode to Map<String, JsonNode>
        return enrichedData.properties().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Builds a JSON array node for feedback scores, excluding timestamp fields.
     * This avoids serialization issues with Instant fields.
     *
     * @param feedbackScores The feedback scores to convert
     * @return An ArrayNode containing the feedback scores without timestamps
     */
    private ArrayNode buildFeedbackScoresNode(List<FeedbackScore> feedbackScores) {
        ArrayNode scoresArray = JsonUtils.getMapper().createArrayNode();
        for (var score : feedbackScores) {
            ObjectNode scoreNode = JsonUtils.getMapper().createObjectNode();
            scoreNode.put("name", score.name());
            Optional.ofNullable(score.categoryName())
                    .ifPresent(categoryName -> scoreNode.put("category_name", categoryName));
            scoreNode.put("value", score.value());
            Optional.ofNullable(score.reason())
                    .ifPresent(reason -> scoreNode.put("reason", reason));
            scoreNode.put("source", score.source().toString());

            scoresArray.add(scoreNode);
        }
        return scoresArray;
    }

    /**
     * Builds a JSON array node for comments, excluding timestamp fields.
     * This avoids serialization issues with Instant fields.
     *
     * @param comments The comments to convert
     * @return An ArrayNode containing the comments without timestamps
     */
    private ArrayNode buildCommentsNode(List<Comment> comments) {
        ArrayNode commentsArray = JsonUtils.getMapper().createArrayNode();
        for (var comment : comments) {
            ObjectNode commentNode = JsonUtils.getMapper().createObjectNode();
            commentNode.put("id", comment.id().toString());
            commentNode.put("text", comment.text());
            // Omit createdAt, lastUpdatedAt, createdBy, lastUpdatedBy as they're not essential for dataset items
            commentsArray.add(commentNode);
        }
        return commentsArray;
    }

    /**
     * Options for span enrichment specifying which metadata to include.
     */
    @Builder(toBuilder = true)
    public record SpanEnrichmentOptions(
            boolean includeTags,
            boolean includeFeedbackScores,
            boolean includeComments,
            boolean includeUsage,
            boolean includeMetadata) {
    }
}
