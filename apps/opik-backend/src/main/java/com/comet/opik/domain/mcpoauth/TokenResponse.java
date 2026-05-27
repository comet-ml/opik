package com.comet.opik.domain.mcpoauth;

public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        String workspaceId,
        String workspaceName) {
}
