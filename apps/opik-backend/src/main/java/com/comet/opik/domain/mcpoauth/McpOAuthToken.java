package com.comet.opik.domain.mcpoauth;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;

@Builder(toBuilder = true)
public record McpOAuthToken(
        @NonNull String id,
        @NonNull String tokenHash,
        @NonNull String type,
        @NonNull String clientId,
        @NonNull String userName,
        @NonNull String workspaceName,
        @NonNull String workspaceId,
        @NonNull String resource,
        @NonNull String familyId,
        String rotatedFromId,
        Instant issuedAt,
        @NonNull Instant expiresAt,
        Instant revokedAt,
        RevokedReason revokedReason) {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
}
