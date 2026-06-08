package com.comet.opik.api.spend;

import com.comet.opik.api.filter.TraceFilter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SpendMetricRequest(
        UUID projectId,
        String projectName,
        @NotNull Instant intervalStart,
        @NotNull Instant intervalEnd,
        @Valid List<TraceFilter> filters,
        @JsonIgnore UUID resolvedProjectId) {

    @AssertTrue(message = "interval_start must be before interval_end") public boolean isStartBeforeEnd() {
        return intervalStart == null || intervalEnd == null || intervalStart.isBefore(intervalEnd);
    }

    @AssertTrue(message = "either project_id or project_name must be provided") public boolean isProjectProvided() {
        return projectId != null || (projectName != null && !projectName.isBlank());
    }
}
