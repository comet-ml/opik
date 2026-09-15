package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Enrols projects in the auto-first-run rollout, or clears them. Internal and cross-workspace: the workspace
 * is resolved from each project id rather than from a request context.
 */
public class AgentInsightsEnrollment {

    // Bounded so a malformed call can't enrol an unintended number of projects in one request.
    public static final int MAX_PROJECTS = 500;

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Request(
            @Schema(description = "True enrols the given projects, false clears their enrolment") @NotNull Boolean enrolled,
            @NotEmpty @Size(max = MAX_PROJECTS) List<UUID> projectIds) {
    }

    @Builder
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Response(
            int enrolled,
            int cleared,
            @Schema(description = "Ids that match no project") Set<UUID> unknownProjectIds,
            @Schema(description = "Ids whose automatic run already happened, so enrolling has no effect") Set<UUID> alreadyRunProjectIds) {
    }
}
