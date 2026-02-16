package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.usagelimit.Quota;
import lombok.Builder;

import java.util.List;
import java.util.Optional;

interface CacheService {

    @Builder(toBuilder = true)
    record AuthCredentials(String userName, String workspaceId, String workspaceName, List<Quota> quotas) {
    }

    void cache(String apiKey, String requestWorkspaceName, List<String> requiredPermissions,
            String userName, String workspaceId, String resolvedWorkspaceName, List<Quota> quotas);

    Optional<AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(String apiKey, String workspaceName,
            List<String> requiredPermissions);
}

class NoopCacheService implements CacheService {

    @Override
    public void cache(String apiKey, String requestWorkspaceName, List<String> requiredPermissions,
            String userName, String workspaceId, String resolvedWorkspaceName, List<Quota> quotas) {
        // no-op
    }

    @Override
    public Optional<CacheService.AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(
            String apiKey, String workspaceName, List<String> requiredPermissions) {
        return Optional.empty();
    }
}