package com.comet.opik.api;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder(toBuilder = true)
public record TraceDetails(
        @NotNull String projectId,
        @NotNull String workspaceId) {
}
