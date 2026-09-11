package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WorkspaceMetricRequest(
        Set<@NotNull UUID> projectIds,
        String name,
        @NotNull Instant intervalStart,
        @NotNull Instant intervalEnd) {

    @AssertTrue(message = "intervalStart must be before intervalEnd") public boolean isStartBeforeEnd() {
        return intervalStart.isBefore(intervalEnd);
    }
}
