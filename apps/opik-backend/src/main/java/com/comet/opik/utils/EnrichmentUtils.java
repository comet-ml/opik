package com.comet.opik.utils;

import com.comet.opik.api.Comment;
import com.comet.opik.api.FeedbackScore;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;

/**
 * Utility class for enrichment operations, providing common methods for building
 * JSON representations of feedback scores and comments for dataset items.
 */
@UtilityClass
public class EnrichmentUtils {

    /**
     * Builds a JSON array node for feedback scores, excluding timestamp fields.
     * This avoids serialization issues with Instant fields and provides a clean
     * representation suitable for dataset items.
     *
     * @param feedbackScores The feedback scores to convert
     * @return An ArrayNode containing the feedback scores without timestamps
     */
    public static ArrayNode buildFeedbackScoresNode(@NonNull List<FeedbackScore> feedbackScores) {
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
     * This avoids serialization issues with Instant fields and provides a clean
     * representation suitable for dataset items.
     *
     * @param comments The comments to convert
     * @return An ArrayNode containing the comments without timestamps
     */
    public static ArrayNode buildCommentsNode(@NonNull List<Comment> comments) {
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
}
