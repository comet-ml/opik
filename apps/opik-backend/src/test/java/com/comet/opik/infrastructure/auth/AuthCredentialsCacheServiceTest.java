package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.RedisConfig;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.redisson.Redisson;
import org.testcontainers.lifecycle.Startables;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AuthCredentialsCacheServiceTest {
    private final AuthCredentialsCacheService cacheService;

    private static final int CACHE_TTL_IN_SECONDS = 1;

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    {
        Startables.deepStart(REDIS).join();
        RedisConfig redisConfig = new RedisConfig();
        redisConfig.setSingleNodeUrl(REDIS.getRedisURI());
        cacheService = new AuthCredentialsCacheService(Redisson.create(redisConfig.build()).reactive(),
                CACHE_TTL_IN_SECONDS);
    }

    @Test
    void testCacheAndRetrieve() {
        String apiKey = RandomStringUtils.secure().nextAlphanumeric(10);
        String workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
        String userName = RandomStringUtils.secure().nextAlphanumeric(10);
        String workspaceId = RandomStringUtils.secure().nextAlphanumeric(10);
        String resolvedWorkspaceName = RandomStringUtils.secure().nextAlphanumeric(10);

        assertThat(cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName).isEmpty())
                .isTrue();

        cacheService.cache(apiKey, workspaceName, userName, workspaceId, resolvedWorkspaceName);

        Optional<CacheService.AuthCredentials> credentials = cacheService
                .resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName);
        assertThat(credentials.isEmpty()).isFalse();
    }
}
