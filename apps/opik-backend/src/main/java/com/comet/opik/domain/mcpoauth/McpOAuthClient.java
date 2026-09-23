package com.comet.opik.domain.mcpoauth;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.util.Set;

@Builder(toBuilder = true)
public record McpOAuthClient(
        @NonNull String id,
        @NonNull String name,
        @NonNull Set<String> redirectUris,
        @Nullable String logoUri,
        @Nullable String softwareId,
        @Nullable String softwareVersion,
        @Nullable String clientUri,
        @Nullable String ownerUserName) {
}
