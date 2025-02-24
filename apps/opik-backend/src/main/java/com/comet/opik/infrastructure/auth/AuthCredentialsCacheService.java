package com.comet.opik.infrastructure.auth;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RequiredArgsConstructor
class AuthCredentialsCacheService implements CacheService {

    private static final String KEY_FORMAT = "authV2-%s-%s";
    private static final String USER_NAME_KEY = "userName";
    private static final String WORKSPACE_ID_KEY = "workspaceId";

    private final @NonNull RedissonReactiveClient redissonClient;
    private final int ttlInSeconds;

    public Optional<AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(
            @NonNull String apiKey, @NonNull String workspaceName) {
        var key = getKey(apiKey, workspaceName);
        RMapReactive<String, String> map = redissonClient.getMap(key);
        return map.getAll(Set.of(USER_NAME_KEY, WORKSPACE_ID_KEY))
                .blockOptional()
                .filter(m -> !m.isEmpty())
                .map(m -> AuthCredentials.builder()
                        .userName(m.get(USER_NAME_KEY))
                        .workspaceId(m.get(WORKSPACE_ID_KEY))
                        .build());
    }

    public void cache(
            @NonNull String apiKey,
            @NonNull String workspaceName,
            @NonNull String userName,
            @NonNull String workspaceId) {
        var key = getKey(apiKey, workspaceName);
        redissonClient.getMap(key).putAll(Map.of(USER_NAME_KEY, userName, WORKSPACE_ID_KEY, workspaceId)).block();
        redissonClient.getMap(key).expire(Duration.ofSeconds(ttlInSeconds)).block();
    }

    private String getKey(String apiKey, String workspaceName) {
        return KEY_FORMAT.formatted(apiKey, workspaceName);
    }
}
