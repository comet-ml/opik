package com.comet.opik.infrastructure.auth;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record UserWorkspace(@NonNull String userName, @NonNull String workspaceId, @NonNull String workspaceName) {
}
