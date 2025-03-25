package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.RedisConfig;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.Redisson;
import org.testcontainers.lifecycle.Startables;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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

    @ParameterizedTest
    @MethodSource
    void testCacheAndRetrieveQuotas(List<Quota> quotas) {
        String apiKey = RandomStringUtils.secure().nextAlphanumeric(10);
        String workspaceName = RandomStringUtils.secure().nextAlphanumeric(10);
        String userName = RandomStringUtils.secure().nextAlphanumeric(10);
        String workspaceId = RandomStringUtils.secure().nextAlphanumeric(10);
        String resolvedWorkspaceName = RandomStringUtils.secure().nextAlphanumeric(10);

        assertThat(cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName).isEmpty())
                .isTrue();

        cacheService.cache(apiKey, workspaceName, userName, workspaceId, resolvedWorkspaceName, quotas);

        Optional<CacheService.AuthCredentials> credentials = cacheService
                .resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName);

        assertThat(credentials.isEmpty()).isFalse();
        assertThat(credentials.get().userName()).isEqualTo(userName);
        assertThat(credentials.get().workspaceId()).isEqualTo(workspaceId);
        assertThat(credentials.get().workspaceName()).isEqualTo(resolvedWorkspaceName);
        assertThat(credentials.get().quotas()).isEqualTo(ListUtils.emptyIfNull(quotas));
    }

    Stream<Arguments> testCacheAndRetrieveQuotas() {
        return Stream.of(
                arguments(named("null quotas", null)),
                arguments(named("empty quotas", List.of())),
                arguments(named("valid quotas", List.of(Quota.builder()
                        .type(Quota.QuotaType.OPIK_SPAN_COUNT)
                        .limit(25_000)
                        .used(24_999)
                        .build()))));
    }
}
