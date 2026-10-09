package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentInsightsGuidanceUpdate(
        // Required so a body missing the key is rejected rather than read as "clear the guidance".
        @NotNull @Size(max = GUIDANCE_MAX_LENGTH) @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Project guidance for Agent Insights runs. Empty or whitespace-only clears it") String guidance) {

    public static final int GUIDANCE_MAX_LENGTH = 5000;
}
