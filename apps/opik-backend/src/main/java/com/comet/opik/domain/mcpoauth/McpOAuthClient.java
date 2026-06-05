package com.comet.opik.domain.mcpoauth;

import lombok.Builder;

import java.time.Instant;
import java.util.Set;

@Builder(toBuilder = true)
public record McpOAuthClient(
        String clientId,
        String name,
        Set<String> redirectUris,
        String logoUri,
        String ownerUserName,
        Instant createdAt,
        String createdBy,
        Instant lastUpdatedAt,
        String lastUpdatedBy,
        Instant revokedAt) {
}
