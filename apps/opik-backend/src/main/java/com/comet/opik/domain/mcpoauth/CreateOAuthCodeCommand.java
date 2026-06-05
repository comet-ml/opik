package com.comet.opik.domain.mcpoauth;

import lombok.Builder;

@Builder(toBuilder = true)
public record CreateOAuthCodeCommand(
        String clientId,
        String userName,
        String workspaceName,
        String workspaceId,
        String codeChallenge,
        String redirectUri,
        String resource) {
}
