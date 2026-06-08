package com.comet.opik.infrastructure.auth;

import lombok.Builder;

@Builder(toBuilder = true)
public record UserWorkspace(String userName, String workspaceId, String workspaceName) {
}
