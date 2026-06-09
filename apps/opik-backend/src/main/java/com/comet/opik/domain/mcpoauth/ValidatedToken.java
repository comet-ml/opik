package com.comet.opik.domain.mcpoauth;

import lombok.Builder;

@Builder(toBuilder = true)
public record ValidatedToken(
        String userName,
        String workspaceId,
        String workspaceName,
        String resource) {
}
