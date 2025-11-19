package com.comet.opik.api.metrics;

import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectMetricRequest(
        @NonNull MetricType metricType,
        @NonNull TimeInterval interval,
        @NonNull Instant intervalStart,
        @NonNull Instant intervalEnd,
        List<TraceFilter> traceFilters,
        List<TraceThreadFilter> threadFilters) {
}
