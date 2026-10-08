package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentInsightsGuidanceUpdate(
        @Schema(maxLength = MAX_LENGTH, description = "Project guidance for Agent Insights runs. Empty or whitespace-only clears it") String guidance) {

    // Checked by the service rather than @Size, so an over-limit body is a 400 as the API promises, not a 422.
    public static final int MAX_LENGTH = 5000;
}
