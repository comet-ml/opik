package com.comet.opik.api.metrics;

import com.comet.opik.api.TimeInterval;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectMetricResponse<T extends Number>(
        UUID projectId,
        MetricType metricType,
        TimeInterval interval,
        List<Results<T>> traces) {

    public static final ProjectMetricResponse<?> EMPTY = ProjectMetricResponse.builder()
            .traces(List.of())
            .build();

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Results<T>(String name, List<Instant> timestamps, List<T> values) {}
}
