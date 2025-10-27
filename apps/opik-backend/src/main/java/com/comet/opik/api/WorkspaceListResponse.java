package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WorkspaceListResponse(
        List<WorkspaceInfo> workspaces) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkspaceInfo(
            String workspaceId,
            String workspaceName,
            String organizationId,
            String workspaceOwner,
            String workspaceCreator,
            Long createdAt,
            Boolean defaultWorkspace,
            Boolean collaborationFeaturesDisabled) {
    }
}

