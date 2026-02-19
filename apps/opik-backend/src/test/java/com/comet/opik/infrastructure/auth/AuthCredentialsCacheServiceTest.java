package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.RedisConfig;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
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
        String apiKey = getRandomId();
        String workspaceName = getRandomId();
        String userName = getRandomId();
        String workspaceId = getRandomId();
        String resolvedWorkspaceName = getRandomId();

        List<String> requiredPermissions = List.of();
        assertThat(cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, requiredPermissions)
                .isEmpty())
                .isTrue();

        cacheService.cache(apiKey, workspaceName, requiredPermissions, userName, workspaceId, resolvedWorkspaceName,
                quotas);

        Optional<CacheService.AuthCredentials> credentials = cacheService
                .resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, requiredPermissions);

        assertThat(credentials.isEmpty()).isFalse();
        assertThat(credentials.get().userName()).isEqualTo(userName);
        assertThat(credentials.get().workspaceId()).isEqualTo(workspaceId);
        assertThat(credentials.get().workspaceName()).isEqualTo(workspaceName); // request workspace name (not stored in workspace metadata)
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

    @Test
    void testMultiplePermissionsRequireAllPermissionKeysPresentInCache() {
        String apiKey = getRandomId();
        String workspaceName = getRandomId();
        String userName = getRandomId();
        String workspaceId = getRandomId();
        List<String> requiredPermissions = List.of(WorkspaceUserPermission.DASHBOARD_VIEW.getValue(),
                WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG.getValue());

        cacheService.cache(apiKey, workspaceName, requiredPermissions, userName, workspaceId, workspaceName, null);

        resolveAndAssertOnValidCache(apiKey, workspaceName, requiredPermissions, userName, workspaceId);
    }

    @Test
    void testMultiplePermissionsMissWhenAnyPermissionKeyMissing() {
        String apiKey = getRandomId();
        String workspaceName = getRandomId();
        String userName = getRandomId();
        String workspaceId = getRandomId();

        List<String> cachedPermissions = List.of(WorkspaceUserPermission.DASHBOARD_VIEW.getValue());
        List<String> resolvedPermissions = List.of(WorkspaceUserPermission.DASHBOARD_VIEW.getValue(),
                WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG.getValue());

        cacheService.cache(apiKey, workspaceName, cachedPermissions, userName, workspaceId, workspaceName, null);
        var resolved = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName,
                resolvedPermissions);

        assertThat(resolved).isEmpty();
    }

    @Test
    void testNoRequiredPermissions() {
        String apiKey = getRandomId();
        String workspaceName = getRandomId();
        String userName = getRandomId();
        String workspaceId = getRandomId();

        var resolved = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, null);
        assertThat(resolved).isEmpty();

        cacheService.cache(apiKey, workspaceName, null, userName, workspaceId, workspaceName, null);

        resolveAndAssertOnValidCache(apiKey, workspaceName, null, userName, workspaceId);
    }

    @Test
    void multiplePermissionsWithSharedWorkspaceMetadataReturnsCachedCredentials() {
        String apiKey = getRandomId();
        String workspaceName = getRandomId();
        String workspaceId = getRandomId();
        String userName = getRandomId();
        List<String> bothPermissions = List.of(WorkspaceUserPermission.DASHBOARD_VIEW.getValue(),
                WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG.getValue());

        // apiKey is coupled with one userName; cache both permissions with same user
        cacheService.cache(apiKey, workspaceName, List.of(WorkspaceUserPermission.DASHBOARD_VIEW.getValue()),
                userName, workspaceId, workspaceName, null);
        cacheService.cache(apiKey, workspaceName, List.of(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG.getValue()),
                userName, workspaceId, workspaceName, null);

        resolveAndAssertOnValidCache(apiKey, workspaceName, bothPermissions, userName, workspaceId);
    }

    private void resolveAndAssertOnValidCache(String apiKey, String workspaceName, List<String> bothPermissions,
            String expectedUserName, String expectedWorkspaceId) {
        var resolved = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, bothPermissions);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().userName()).isEqualTo(expectedUserName);
        assertThat(resolved.get().workspaceId()).isEqualTo(expectedWorkspaceId);
    }

    private String getRandomId() {
        return RandomStringUtils.secure().nextAlphanumeric(10);
    }

}
