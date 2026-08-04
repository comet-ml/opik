package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.infrastructure.RedisConfig;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.testcontainers.lifecycle.Startables;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class AuthCredentialsCacheServiceTest {

    private static final int CACHE_TTL_IN_SECONDS = 1;

    private final AuthCredentialsCacheService cacheService;
    private final RedissonReactiveClient redisClient;

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    {
        Startables.deepStart(REDIS).join();
        RedisConfig redisConfig = new RedisConfig();
        redisConfig.setSingleNodeUrl(REDIS.getRedisURI());
        redisClient = Redisson.create(redisConfig.build()).reactive();
        cacheService = new AuthCredentialsCacheService(redisClient, CACHE_TTL_IN_SECONDS);
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

        cacheService.cache(apiKey, workspaceName, requiredPermissions,
                CacheService.AuthCredentials.builder()
                        .userName(userName).workspaceId(workspaceId)
                        .workspaceName(resolvedWorkspaceName).quotas(quotas).build());

        Optional<CacheService.AuthCredentials> credentials = cacheService
                .resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, requiredPermissions);

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

    @Test
    void testMultiplePermissionsRequireAllPermissionKeysPresentInCache() {
        String apiKey = getRandomId();
        String workspaceName = getRandomId();
        String userName = getRandomId();
        String workspaceId = getRandomId();
        List<String> requiredPermissions = List.of(WorkspaceUserPermission.DASHBOARD_VIEW.getValue(),
                WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG.getValue());

        cacheService.cache(apiKey, workspaceName, requiredPermissions,
                CacheService.AuthCredentials.builder()
                        .userName(userName).workspaceId(workspaceId)
                        .workspaceName(workspaceName).build());

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

        cacheService.cache(apiKey, workspaceName, cachedPermissions,
                CacheService.AuthCredentials.builder()
                        .userName(userName).workspaceId(workspaceId)
                        .workspaceName(workspaceName).build());
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

        cacheService.cache(apiKey, workspaceName, null,
                CacheService.AuthCredentials.builder()
                        .userName(userName).workspaceId(workspaceId)
                        .workspaceName(workspaceName).build());

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

        cacheService.cache(apiKey, workspaceName, List.of(WorkspaceUserPermission.DASHBOARD_VIEW.getValue()),
                CacheService.AuthCredentials.builder()
                        .userName(userName).workspaceId(workspaceId)
                        .workspaceName(workspaceName).build());
        cacheService.cache(apiKey, workspaceName, List.of(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG.getValue()),
                CacheService.AuthCredentials.builder()
                        .userName(userName).workspaceId(workspaceId)
                        .workspaceName(workspaceName).build());

        resolveAndAssertOnValidCache(apiKey, workspaceName, bothPermissions, userName, workspaceId);
    }

    @Test
    void permissionsRequireBothV3AndV2CachePresent() {
        String apiKey = getRandomId();
        String workspaceName = getRandomId();
        String userName = getRandomId();
        String workspaceId = getRandomId();
        List<String> permissions = List.of(WorkspaceUserPermission.DASHBOARD_VIEW.getValue());

        cacheService.cache(apiKey, workspaceName, permissions,
                CacheService.AuthCredentials.builder()
                        .userName(userName).workspaceId(workspaceId)
                        .workspaceName(workspaceName).build());
        resolveAndAssertOnValidCache(apiKey, workspaceName, permissions, userName, workspaceId);

        var resolvedWithDifferentApiKey = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(
                getRandomId(), workspaceName, permissions);
        assertThat(resolvedWithDifferentApiKey).isEmpty();
    }

    @Test
    void v2CacheWorksWithoutPermissions() {
        String apiKey = getRandomId();
        String workspaceName = getRandomId();
        String userName = getRandomId();
        String workspaceId = getRandomId();
        String resolvedWorkspaceName = getRandomId();

        cacheService.cache(apiKey, workspaceName, null,
                CacheService.AuthCredentials.builder()
                        .userName(userName).workspaceId(workspaceId)
                        .workspaceName(resolvedWorkspaceName).build());

        var resolved = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, null);
        assertThat(resolved).isPresent();
        assertThat(resolved.get().userName()).isEqualTo(userName);
        assertThat(resolved.get().workspaceId()).isEqualTo(workspaceId);
        assertThat(resolved.get().workspaceName()).isEqualTo(resolvedWorkspaceName);

        var resolvedWithEmptyList = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName,
                List.of());
        assertThat(resolvedWithEmptyList).isPresent();
        assertThat(resolvedWithEmptyList.get().userName()).isEqualTo(userName);
    }

    /**
     * A cache record written by a pre-upgrade backend still carries the removed {@code opikVersion}
     * hash field. Resolution requests only the modeled fields, so the stale field must be ignored
     * and the credentials resolve normally — old entries keep working across a rolling upgrade
     * until they age out.
     */
    @Test
    void resolveV2Cache__whenRecordHasStaleLegacyField__thenIgnoredAndResolves() {
        var apiKey = "apiKey-" + UUID.randomUUID();
        var workspaceName = "workspace-" + getRandomId();
        var userName = "user-" + getRandomId();
        var workspaceId = UUID.randomUUID().toString();

        // Built in the pre-upgrade storage shape on purpose; the key literal mirrors the SUT's
        // private V2_KEY_FORMAT, which can't be referenced without widening its visibility.
        var legacyRecord = redisClient.<String, String>getMap("authV2-%s-%s".formatted(apiKey, workspaceName));
        legacyRecord.putAll(Map.of(
                "userName", userName,
                "workspaceId", workspaceId,
                "workspaceName", workspaceName,
                "quotas", "[]",
                "opikVersion", "version_1")).block();

        var resolved = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, null);

        var expected = CacheService.AuthCredentials.builder()
                .userName(userName)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .quotas(List.of())
                .build();
        assertThat(resolved).contains(expected);
    }

    private void resolveAndAssertOnValidCache(String apiKey, String workspaceName, List<String> bothPermissions,
            String expectedUserName, String expectedWorkspaceId) {
        var resolved = cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, bothPermissions);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().userName()).isEqualTo(expectedUserName);
        assertThat(resolved.get().workspaceId()).isEqualTo(expectedWorkspaceId);
    }

    private String getRandomId() {
        return RandomStringUtils.secure().nextAlphanumeric(32);
    }
}
