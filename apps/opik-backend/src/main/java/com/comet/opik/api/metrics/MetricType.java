package com.comet.opik.api.metrics;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public enum MetricType {
    FEEDBACK_SCORES,
    NUMBER_OF_TRACES,
    TOKEN_USAGE,
}
