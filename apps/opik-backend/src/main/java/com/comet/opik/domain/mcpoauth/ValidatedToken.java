package com.comet.opik.domain.mcpoauth;

public record ValidatedToken(
        String userName,
        String workspaceId,
        String workspaceName,
        String resource) {
}
