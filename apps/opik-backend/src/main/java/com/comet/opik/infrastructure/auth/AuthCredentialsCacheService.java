package com.comet.opik.infrastructure.auth;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RListReactive;
import org.redisson.api.RedissonReactiveClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
class AuthCredentialsCacheService implements CacheService {

    public static final String KEY_FORMAT = "auth-%s-%s";
    private final RedissonReactiveClient redissonClient;
    private final int ttlInSeconds;

    public Optional<AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(@NonNull String apiKey,
            @NonNull String workspaceName) {
        String key = KEY_FORMAT.formatted(apiKey, workspaceName);

        RListReactive<String> bucket = redissonClient.getList(key);

        return bucket
                .readAll()
                .blockOptional()
                .filter(pair -> pair.size() == 2)
                .map(pair -> new AuthCredentials(pair.getFirst(), pair.getLast()));
    }

    public void cache(@NonNull String apiKey, @NonNull String workspaceName, @NonNull String userName,
            @NonNull String workspaceId) {
        String key = KEY_FORMAT.formatted(apiKey, workspaceName);
        redissonClient.getList(key).addAll(List.of(userName, workspaceId)).block();
        redissonClient.getList(key).expire(Duration.ofSeconds(ttlInSeconds)).block();
    }

}
