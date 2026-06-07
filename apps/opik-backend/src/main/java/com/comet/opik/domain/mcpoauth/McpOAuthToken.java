package com.comet.opik.domain.mcpoauth;

import lombok.Builder;

import java.time.Instant;

@Builder(toBuilder = true)
public record McpOAuthToken(
        String id,
        String tokenHash,
        String type,
        String clientId,
        String userName,
        String workspaceName,
        String workspaceId,
        String resource,
        String familyId,
        String rotatedFromId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt,
        String revokedReason) {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
}
