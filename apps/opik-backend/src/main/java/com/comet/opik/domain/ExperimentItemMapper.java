package com.comet.opik.domain;

import com.comet.opik.api.ExperimentItem;
import com.comet.opik.utils.JsonUtils;
import io.r2dbc.spi.Result;
import lombok.experimental.UtilityClass;
import org.reactivestreams.Publisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.CommentResultMapper.getComments;
import static com.comet.opik.domain.FeedbackScoreMapper.getFeedbackScores;

@UtilityClass
class ExperimentItemMapper {
    public static Publisher<ExperimentItem> mapToExperimentItem(Result result) {
        return result.map((row, rowMetadata) -> ExperimentItem.builder()
                .id(row.get("id", UUID.class))
                .experimentId(row.get("experiment_id", UUID.class))
                .datasetItemId(row.get("dataset_item_id", UUID.class))
                .traceId(row.get("trace_id", UUID.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdAt(row.get("created_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build());
    }

    public static Publisher<ExperimentItem> mapToExperimentItemFullContent(Result result) {
        return result.map((row, rowMetadata) -> ExperimentItem.builder()
                .id(row.get("id", UUID.class))
                .experimentId(row.get("experiment_id", UUID.class))
                .datasetItemId(row.get("dataset_item_id", UUID.class))
                .traceId(row.get("trace_id", UUID.class))
                .input(Optional.ofNullable(row.get("input", String.class))
                        .filter(str -> !str.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .output(Optional.ofNullable(row.get("output", String.class))
                        .filter(str -> !str.isBlank())
                        .map(JsonUtils::getJsonNodeFromString)
                        .orElse(null))
                .feedbackScores(getFeedbackScores(row.get("feedback_scores_array", List[].class)))
                .comments(getComments(row.get("comments_array_agg", List[].class)))
                .duration(row.get("duration", Double.class))
                .totalEstimatedCost(row.get("total_estimated_cost", BigDecimal.class).compareTo(BigDecimal.ZERO) == 0
                        ? null
                        : row.get("total_estimated_cost", BigDecimal.class))
                .usage(row.get("usage", Map.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdAt(row.get("created_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build());
    }

}
