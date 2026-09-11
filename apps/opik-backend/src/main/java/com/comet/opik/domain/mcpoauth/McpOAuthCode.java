package com.comet.opik.domain.mcpoauth;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;

@Builder(toBuilder = true)
public record McpOAuthCode(
        @NonNull String id,
        @NonNull String codeHash,
        @NonNull String clientId,
        @NonNull String userName,
        @NonNull String workspaceName,
        @NonNull String workspaceId,
        @NonNull String codeChallenge,
        @NonNull String codeChallengeMethod,
        @NonNull String redirectUri,
        @NonNull String resource,
        Instant createdAt,
        @NonNull Instant expiresAt,
        Instant usedAt) {
}
