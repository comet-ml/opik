package com.comet.opik.api.metrics;

import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProjectMetricRequest(
        @NonNull MetricType metricType,
        @NonNull TimeInterval interval,
        @NonNull Instant intervalStart,
        Instant intervalEnd,
        List<SpanFilter> spanFilters,
        List<TraceFilter> traceFilters,
        List<TraceThreadFilter> threadFilters,
        @JsonIgnore UUID uuidFromTime,
        @JsonIgnore UUID uuidToTime) {
}
