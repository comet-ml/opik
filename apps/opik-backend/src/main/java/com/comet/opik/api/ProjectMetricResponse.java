package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectMetricResponse(
        String projectId,
        MetricType metricType,
        TimeInterval interval,
        List<Results> traces) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public enum MetricType {
        FEEDBACK_SCORES,
        NUMBER_OF_TRACES,
        TOKEN_USAGE,
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Results(String name, List<Instant> timestamps, List<Double> values) {}
}
