package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ValueEntry(BigDecimal value, String reason, String categoryName, ScoreSource source,
        Instant lastUpdatedAt, String spanType, String spanId) {
    public ValueEntry(BigDecimal value, String reason, String categoryName, ScoreSource source,
            Instant lastUpdatedAt) {
        this(value, reason, categoryName, source, lastUpdatedAt, null, null);
    }

    public ValueEntry(BigDecimal value, String reason, String categoryName, ScoreSource source,
            Instant lastUpdatedAt, String spanType) {
        this(value, reason, categoryName, source, lastUpdatedAt, spanType, null);
    }
}