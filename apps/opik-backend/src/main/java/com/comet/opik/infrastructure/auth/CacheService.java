package com.comet.opik.infrastructure.auth;

import lombok.Builder;

import java.util.Optional;

interface CacheService {

    @Builder(toBuilder = true)
    record AuthCredentials(String userName, String workspaceId) {
    }

    void cache(String apiKey, String workspaceName, String userName, String workspaceId);

    Optional<AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(String apiKey, String workspaceName);
}

class NoopCacheService implements CacheService {

    @Override
    public void cache(String apiKey, String workspaceName, String userName, String workspaceId) {
        // no-op
    }

    @Override
    public Optional<AuthCredentialsCacheService.AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(
            String apiKey, String workspaceName) {
        return Optional.empty();
    }
}