package com.comet.opik.api.metrics;

import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.filter.SpanFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Request for workspace-level span metrics aggregated across projects. When {@code projectIds} is empty, the service
 * resolves it to every project in the workspace before querying, so the aggregation always runs against an explicit,
 * bounded project set (never an unconstrained workspace-wide span scan); otherwise only the given projects are used.
 * {@code intervalEnd} is optional and defaults to "now" server-side, mirroring the per-project metrics endpoint.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WorkspaceSpanMetricRequest(
        Set<@NotNull UUID> projectIds,
        MetricType metricType,
        TimeInterval interval,
        @Valid BreakdownConfig breakdown,
        List<SpanFilter> filters,
        @NotNull Instant intervalStart,
        Instant intervalEnd) {

    @AssertTrue(message = "intervalStart must be before intervalEnd") public boolean isStartBeforeEnd() {
        return intervalEnd == null || intervalStart.isBefore(intervalEnd);
    }

    public boolean hasBreakdown() {
        return Optional.ofNullable(breakdown)
                .map(BreakdownQueryBuilder::isEnabled)
                .orElse(false);
    }
}
