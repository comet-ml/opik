package com.comet.opik.domain;

import lombok.Builder;
import org.jdbi.v3.json.Json;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder(toBuilder = true)
public record NumericalFeedbackDefinitionDefinitionModel(
        UUID id,
        String name,
        String description,
        @Json NumericalFeedbackDetail details,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy)
        implements
            FeedbackDefinitionModel<NumericalFeedbackDefinitionDefinitionModel.NumericalFeedbackDetail> {

    @Builder(toBuilder = true)
    public record NumericalFeedbackDetail(BigDecimal min, BigDecimal max) {
    }

    public FeedbackType type() {
        return FeedbackType.NUMERICAL;
    }

}
