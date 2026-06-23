package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UsageByWorkspaceProjectUserResponse(
        List<WorkspaceProjectUserCount> breakdown) {

    public static UsageByWorkspaceProjectUserResponse empty() {
        return new UsageByWorkspaceProjectUserResponse(List.of());
    }

    /**
     * A single breakdown row. {@code projectName} is resolved from the state DB and may be
     * {@code null} when the project was deleted after the entities were created.
     */
    @Builder(toBuilder = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkspaceProjectUserCount(
            String workspaceId,
            UUID projectId,
            String projectName,
            String user,
            long count) {
    }
}
