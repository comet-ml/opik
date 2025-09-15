package com.comet.opik.domain;

import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Builder(toBuilder = true)
public record CategoricalFeedbackDefinitionDefinitionModel(
        UUID id,
        String name,
        String description,
        @Json CategoricalFeedbackDetail details,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            FeedbackDefinitionModel<CategoricalFeedbackDefinitionDefinitionModel.CategoricalFeedbackDetail> {

    @Builder(toBuilder = true)
    public record CategoricalFeedbackDetail(Map<String, Double> categories) {
    }

    public FeedbackDefinitionModel.FeedbackType type() {
        return FeedbackDefinitionModel.FeedbackType.CATEGORICAL;
    }

}
