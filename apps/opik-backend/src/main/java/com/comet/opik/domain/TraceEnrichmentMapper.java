package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.utils.EnrichmentUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Mapper for enriching trace data with additional metadata.
 */
@UtilityClass
public class TraceEnrichmentMapper {

    /**
     * Enriches trace data with additional metadata based on the provided options.
     *
     * @param trace The trace to enrich
     * @param spans The spans associated with the trace
     * @param options Options specifying which metadata to include
     * @return A map of field names to their JSON values
     */
    public static Map<String, JsonNode> enrichTraceData(
            @NonNull Trace trace,
            @NonNull List<Span> spans,
            @NonNull TraceEnrichmentOptions options) {

        ObjectNode enrichedData = JsonUtils.getMapper().createObjectNode();

        // Always include input and output
        enrichedData.set("input", JsonUtils.getMapper().valueToTree(trace.input()));
        Optional.ofNullable(trace.output())
                .ifPresent(output -> enrichedData.set("expected_output", JsonUtils.getMapper().valueToTree(output)));

        // Include spans if requested
        if (options.includeSpans() && !spans.isEmpty()) {
            ArrayNode spansArray = JsonUtils.getMapper().createArrayNode();
            for (Span span : spans) {
                ObjectNode spanNode = JsonUtils.getMapper().createObjectNode();
                spanNode.put("id", span.id().toString());
                spanNode.put("name", span.name());
                spanNode.put("type", span.type().toString());
                Optional.ofNullable(span.parentSpanId())
                        .ifPresent(parentSpanId -> spanNode.put("parent_span_id", parentSpanId.toString()));
                spanNode.set("input", JsonUtils.getMapper().valueToTree(span.input()));
                Optional.ofNullable(span.output())
                        .ifPresent(output -> spanNode.set("output", JsonUtils.getMapper().valueToTree(output)));
                Optional.ofNullable(span.startTime())
                        .ifPresent(startTime -> spanNode.put("start_time", startTime.toString()));
                Optional.ofNullable(span.endTime())
                        .ifPresent(endTime -> spanNode.put("end_time", endTime.toString()));
                Optional.ofNullable(span.metadata())
                        .ifPresent(metadata -> spanNode.set("metadata", JsonUtils.getMapper().valueToTree(metadata)));
                Optional.ofNullable(span.feedbackScores())
                        .filter(scores -> !scores.isEmpty())
                        .ifPresent(scores -> spanNode.set("feedback_scores",
                                EnrichmentUtils.buildFeedbackScoresNode(scores)));
                Optional.ofNullable(span.comments())
                        .filter(comments -> !comments.isEmpty())
                        .ifPresent(comments -> spanNode.set("comments",
                                EnrichmentUtils.buildCommentsNode(comments)));
                spansArray.add(spanNode);
            }
            enrichedData.set("spans", spansArray);
        }

        // Include tags if requested
        if (options.includeTags()) {
            Optional.ofNullable(trace.tags())
                    .filter(tags -> !tags.isEmpty())
                    .ifPresent(tags -> enrichedData.set("tags", JsonUtils.getMapper().valueToTree(tags)));
        }

        // Include feedback scores if requested
        if (options.includeFeedbackScores()) {
            Optional.ofNullable(trace.feedbackScores())
                    .filter(scores -> !scores.isEmpty())
                    .ifPresent(scores -> enrichedData.set("feedback_scores",
                            EnrichmentUtils.buildFeedbackScoresNode(scores)));
        }

        // Include comments if requested
        if (options.includeComments()) {
            Optional.ofNullable(trace.comments())
                    .filter(comments -> !comments.isEmpty())
                    .ifPresent(comments -> enrichedData.set("comments",
                            EnrichmentUtils.buildCommentsNode(comments)));
        }

        // Include usage if requested
        if (options.includeUsage()) {
            Optional.ofNullable(trace.usage())
                    .filter(usage -> !usage.isEmpty())
                    .ifPresent(usage -> enrichedData.set("usage", JsonUtils.getMapper().valueToTree(usage)));
        }

        // Include metadata if requested
        if (options.includeMetadata()) {
            Optional.ofNullable(trace.metadata())
                    .ifPresent(metadata -> enrichedData.set("metadata", JsonUtils.getMapper().valueToTree(metadata)));
        }

        // Convert ObjectNode to Map<String, JsonNode>
        return enrichedData.properties().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
