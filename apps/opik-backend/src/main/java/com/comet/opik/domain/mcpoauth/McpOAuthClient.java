package com.comet.opik.domain.mcpoauth;

import lombok.Builder;

import java.util.Set;

@Builder(toBuilder = true)
public record McpOAuthClient(
        String clientId,
        String name,
        Set<String> redirectUris,
        String logoUri,
        String ownerUserName) {
}
