package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record AgentInsightsIssueUpdate(
        @NotNull UUID projectId,
        @NotNull AgentInsightsIssueStatus status,
        @Size(max = CLOSE_NOTE_MAX_LENGTH) @Schema(description = "Why the issue is closed as not useful. Stored only with status closed; any other status clears it") String closeNote) {

    public static final int CLOSE_NOTE_MAX_LENGTH = 500;
}
