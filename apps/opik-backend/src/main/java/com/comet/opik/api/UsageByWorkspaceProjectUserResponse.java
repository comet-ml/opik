package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.UUID;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UsageByWorkspaceProjectUserResponse(
        @NonNull List<WorkspaceProjectUserCount> breakdown) {

    public static UsageByWorkspaceProjectUserResponse empty() {
        return UsageByWorkspaceProjectUserResponse.builder().breakdown(List.of()).build();
    }

    @Builder(toBuilder = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WorkspaceProjectUserCount(
            @NonNull String workspaceId,
            @NonNull UUID projectId,
            @NonNull String user,
            long count) {
    }
}
