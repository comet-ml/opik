package com.comet.opik.api.metrics;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.TimeInterval;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectMetricResponse(
        UUID projectId,
        MetricType metricType,
        TimeInterval interval,
        List<Results> results) {

    public static final ProjectMetricResponse EMPTY = ProjectMetricResponse.builder()
            .results(List.of())
            .build();

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Results(String name, List<DataPoint> data) {
    }
}
