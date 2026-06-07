package com.comet.opik.domain.mcpoauth;

import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record McpOAuthCode(
        String id,
        String codeHash,
        String clientId,
        String userName,
        String workspaceName,
        String workspaceId,
        String codeChallenge,
        String codeChallengeMethod,
        String redirectUri,
        String resource,
        Instant createdAt,
        Instant expiresAt,
        Instant usedAt) {
}
