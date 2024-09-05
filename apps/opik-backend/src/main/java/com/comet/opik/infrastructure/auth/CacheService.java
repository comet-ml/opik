package com.comet.opik.infrastructure.auth;

import java.util.Optional;

interface CacheService {

    record AuthCredentials(String userName, String workspaceId) {
    }

    void cache(String apiKey, String userName, String workspaceId);
    Optional<AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(String apiKey);
}

class NoopCacheService implements CacheService {

    @Override
    public void cache(String apiKey, String userName, String workspaceId) {
        // no-op
    }

    @Override
    public Optional<AuthCredentialsCacheService.AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(
            String apiKey) {
        return Optional.empty();
    }
}