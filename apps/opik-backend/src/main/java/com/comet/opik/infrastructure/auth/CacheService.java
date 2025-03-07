package com.comet.opik.infrastructure.auth;

import lombok.Builder;

import java.util.Optional;

interface CacheService {

    @Builder(toBuilder = true)
    record AuthCredentials(String userName, String workspaceId, String workspaceName) {
    }

    void cache(String apiKey, String requestWorkspaceName, String userName, String workspaceId,
            String resolvedWorkspaceName);

    Optional<AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(String apiKey, String workspaceName);
}

class NoopCacheService implements CacheService {

    @Override
    public void cache(String apiKey, String requestWorkspaceName, String userName, String workspaceId,
            String resolvedWorkspaceName) {
        // no-op
    }

    @Override
    public Optional<AuthCredentialsCacheService.AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(
            String apiKey, String workspaceName) {
        return Optional.empty();
    }
}