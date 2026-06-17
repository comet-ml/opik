package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

/**
 * Partial update for an Agent Insights job. Only the provided (non-null) fields are applied; today the
 * single mutable field is {@code status} (enable/disable), but the shape is a PATCH so more fields can
 * be added without changing the contract.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentInsightsJobUpdate(
        @NotNull @Schema(description = "New status for the job") AgentInsightsJob.Status status) {
}
