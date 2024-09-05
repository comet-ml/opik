package com.comet.opik.infrastructure.auth;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RedissonReactiveClient;

import java.time.Duration;
import java.util.Optional;

@RequiredArgsConstructor
class AuthCredentialsCacheService implements CacheService {

    public static final String DELIMITER = ";;;";
    private final RedissonReactiveClient redissonClient;
    private final int ttlInSeconds;

    public Optional<AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(@NonNull String apiKey) {
        RBucketReactive<String> bucket = redissonClient.getBucket(apiKey);

        return bucket.get()
                .blockOptional()
                .filter(pair -> !pair.isBlank())
                .map(pair -> pair.split(DELIMITER))
                .filter(pair -> pair.length == 2)
                .map(pair -> new AuthCredentials(pair[0], pair[1]));
    }

    public void cache(@NonNull String apiKey, @NonNull String userName, @NonNull String workspaceId) {
        redissonClient.getBucket(apiKey)
                .set("%s%s%s".formatted(userName, DELIMITER, workspaceId), Duration.ofSeconds(ttlInSeconds))
                .block();
    }

}
