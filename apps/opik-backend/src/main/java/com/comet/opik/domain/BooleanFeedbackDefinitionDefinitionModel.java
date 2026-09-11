package com.comet.opik.domain;

import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
public record BooleanFeedbackDefinitionDefinitionModel(
        UUID id,
        String name,
        String description,
        @Json BooleanFeedbackDetail details,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            FeedbackDefinitionModel<BooleanFeedbackDefinitionDefinitionModel.BooleanFeedbackDetail> {

    @Builder(toBuilder = true)
    public record BooleanFeedbackDetail(String trueLabel, String falseLabel) {
    }

    public FeedbackDefinitionModel.FeedbackType type() {
        return FeedbackDefinitionModel.FeedbackType.BOOLEAN;
    }

}
