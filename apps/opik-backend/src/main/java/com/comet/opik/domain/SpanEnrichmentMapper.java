package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.utils.EnrichmentUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Mapper for enriching span data with additional metadata.
 */
@UtilityClass
public class SpanEnrichmentMapper {

    /**
     * Enriches span data with additional metadata based on the provided options.
     *
     * @param span The span to enrich
     * @param options Options specifying which metadata to include
     * @return A map of field names to their JSON values
     */
    public static Map<String, JsonNode> enrichSpanData(
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
                    .ifPresent(scores -> enrichedData.set("feedback_scores",
                            EnrichmentUtils.buildFeedbackScoresNode(scores)));
        }

        // Include comments if requested
        if (options.includeComments()) {
            Optional.ofNullable(span.comments())
                    .filter(comments -> !comments.isEmpty())
                    .ifPresent(comments -> enrichedData.set("comments",
                            EnrichmentUtils.buildCommentsNode(comments)));
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
}
