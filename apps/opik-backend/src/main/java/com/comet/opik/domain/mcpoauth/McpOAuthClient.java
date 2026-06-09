package com.comet.opik.domain.mcpoauth;

import lombok.Builder;
import lombok.NonNull;

import java.util.Set;

@Builder(toBuilder = true)
public record McpOAuthClient(
        @NonNull String id,
        @NonNull String name,
        @NonNull Set<String> redirectUris,
        String logoUri,
        String ownerUserName) {
}
