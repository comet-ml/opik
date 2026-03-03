package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.utils.JsonUtils;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.redisson.api.RBatchReactive;
import org.redisson.api.RBucketsReactive;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Uses 2 caches:
 * 1. Workspace metadata cache (v2). Data type: Hash. Key pattern: authV2-{apiKey}-{workspaceName},
 *    value: Map with keys 'userName', 'workspaceId', 'workspaceName', 'quotas'
 * 2. Permission access cache (v3). Data type: String. Key pattern: authV3-{apiKey}-{workspaceName}-{permission},
 *    value: userName. Only used when requiredPermissions are specified.
 */
@RequiredArgsConstructor
class AuthCredentialsCacheService implements CacheService {

    private static final String V2_KEY_FORMAT = "authV2-%s-%s";
    private static final String V3_PERMISSION_KEY_FORMAT = "authV3-%s-%s-%s";

    private static final String USER_NAME_KEY = "userName";
    private static final String WORKSPACE_ID_KEY = "workspaceId";
    private static final String WORKSPACE_NAME_KEY = "workspaceName";
    private static final String QUOTAS_KEY = "quotas";
    private static final Set<String> V2_MAP_FIELDS = Set.of(USER_NAME_KEY, WORKSPACE_ID_KEY, WORKSPACE_NAME_KEY,
            QUOTAS_KEY);

    private final @NonNull RedissonReactiveClient redissonClient;
    private final int ttlInSeconds;

    @Override
    public Optional<AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(
            @NonNull String apiKey, @NonNull String workspaceName, List<String> requiredPermissions) {
        if (CollectionUtils.isNotEmpty(requiredPermissions)
                && !hasAllPermissions(apiKey, workspaceName, requiredPermissions)) {
            return Optional.empty();
        }
        return resolveFromV2Cache(apiKey, workspaceName);
    }

    private Optional<AuthCredentials> resolveFromV2Cache(String apiKey, String workspaceName) {
        String key = getV2Key(apiKey, workspaceName);
        RMapReactive<String, String> map = redissonClient.getMap(key);
        return map.getAll(V2_MAP_FIELDS)
                .blockOptional()
                .filter(m -> !m.isEmpty())
                .map(m -> AuthCredentials.builder()
                        .userName(m.get(USER_NAME_KEY))
                        .workspaceId(m.get(WORKSPACE_ID_KEY))
                        .workspaceName(m.get(WORKSPACE_NAME_KEY))
                        .quotas(getQuotas(m))
                        .build());
    }

    private boolean hasAllPermissions(String apiKey, String workspaceName, List<String> requiredPermissions) {
        List<String> requiredPermissionKeys = requiredPermissions.stream()
                .map(permission -> getV3PermissionKey(apiKey, workspaceName, permission))
                .toList();

        RBucketsReactive buckets = redissonClient.getBuckets();
        Map<String, Object> bucketMap = buckets.get(requiredPermissionKeys.toArray(new String[0])).block();
        if (bucketMap == null) {
            return false;
        }
        long presentPermissionsCount = requiredPermissionKeys.stream()
                .map(bucketMap::get)
                .filter(Objects::nonNull)
                .count();
        return presentPermissionsCount == requiredPermissionKeys.size();
    }

    @Override
    public void cache(
            @NonNull String apiKey,
            @NonNull String requestWorkspaceName,
            List<String> requiredPermissions,
            @NonNull String userName,
            @NonNull String workspaceId,
            String resolvedWorkspaceName,
            List<Quota> quotas) {
        Duration ttl = Duration.ofSeconds(ttlInSeconds);

        String v2Key = getV2Key(apiKey, requestWorkspaceName);
        Map<String, String> v2Entry = Map.of(
                USER_NAME_KEY, userName,
                WORKSPACE_ID_KEY, workspaceId,
                WORKSPACE_NAME_KEY, Optional.ofNullable(resolvedWorkspaceName).orElse(requestWorkspaceName),
                QUOTAS_KEY, JsonUtils.writeValueAsString(Optional.ofNullable(quotas).orElse(List.of())));

        RBatchReactive batch = redissonClient.createBatch();
        RMapReactive<String, String> v2Map = batch.getMap(v2Key);
        v2Map.putAll(v2Entry);
        v2Map.expire(ttl);

        if (CollectionUtils.isNotEmpty(requiredPermissions)) {
            for (String permission : requiredPermissions) {
                String permKey = getV3PermissionKey(apiKey, requestWorkspaceName, permission);
                batch.getBucket(permKey).set(userName, ttl);
            }
        }

        batch.execute().block();
    }

    private String getV2Key(String apiKey, String workspaceName) {
        return V2_KEY_FORMAT.formatted(apiKey, workspaceName);
    }

    private String getV3PermissionKey(String apiKey, String workspaceName, String permissionName) {
        return V3_PERMISSION_KEY_FORMAT.formatted(apiKey, workspaceName, permissionName);
    }

    private List<Quota> getQuotas(Map<String, String> redisMap) {
        var rawQuotas = Optional.ofNullable(redisMap.get(QUOTAS_KEY)).orElse("[]");
        return JsonUtils.readCollectionValue(rawQuotas, List.class, Quota.class);
    }

}
