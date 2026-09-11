package com.comet.opik.domain.mcpoauth;

import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record CreateOAuthCodeCommand(
        @NonNull String clientId,
        @NonNull String userName,
        @NonNull String workspaceName,
        @NonNull String workspaceId,
        @NonNull String codeChallenge,
        @NonNull String redirectUri,
        @NonNull String resource) {
}
