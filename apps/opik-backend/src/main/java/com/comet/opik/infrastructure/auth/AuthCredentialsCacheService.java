package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.utils.JsonUtils;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
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
 * 1. User access cache. Data type: String. Key pattern: authV3-{apiKey}-{workspaceName}-{singlePermission}, value: userName
 * 2. Workspace metadata cache. Data type: Hash. Key pattern: authV3-ws-{workspaceName},
 * value: Map with expected keys of 'workspaceId' and 'quotas'
 */
@RequiredArgsConstructor
class AuthCredentialsCacheService implements CacheService {

    private static final String USER_ACCESS_KEY_FORMAT = "authV3-%s-%s-%s";
    private static final String WORKSPACE_METADATA_KEY_FORMAT = "authV3-ws-%s";

    private static final String WORKSPACE_ID_KEY = "workspaceId";
    private static final String QUOTAS_KEY = "quotas";
    private static final Set<String> WORKSPACE_MAP_FIELDS = Set.of(WORKSPACE_ID_KEY, QUOTAS_KEY);

    private final @NonNull RedissonReactiveClient redissonClient;
    private final int ttlInSeconds;

    @Override
    public Optional<AuthCredentials> resolveApiKeyUserAndWorkspaceIdFromCache(
            @NonNull String apiKey, @NonNull String workspaceName, List<String> requiredPermissions) {
        Optional<String> userName = getUserNameFromAccessCache(apiKey, workspaceName, requiredPermissions);
        if (userName.isEmpty()) {
            return Optional.empty();
        }

        Optional<WorkspaceMetadataCache> workspaceMetadata = getWorkspaceMetadataCache(workspaceName);
        return workspaceMetadata.map(workspaceMetadataCache -> AuthCredentials.builder()
                .userName(userName.get())
                .workspaceId(workspaceMetadataCache.workspaceId())
                .workspaceName(workspaceName)
                .quotas(workspaceMetadataCache.quotas())
                .build());

    }

    private Optional<WorkspaceMetadataCache> getWorkspaceMetadataCache(String workspaceName) {
        String workspaceMetadataKey = getWorkspaceMetadataKey(workspaceName);
        RMapReactive<String, String> workspaceMetadataParams = redissonClient.getMap(workspaceMetadataKey);
        return workspaceMetadataParams.getAll(WORKSPACE_MAP_FIELDS)
                .blockOptional()
                .filter(m -> !m.isEmpty())
                .map(params -> WorkspaceMetadataCache.builder()
                        .workspaceId(params.get(WORKSPACE_ID_KEY))
                        .quotas(getQuotas(params))
                        .build());
    }

    /**
     * Fetches access entries for all permissions in a single Redis MGET, verifies all are present and returns the username.
     */
    private Optional<String> getUserNameFromAccessCache(String apiKey, String workspaceName,
            List<String> requiredPermissions) {
        List<String> permissionKeys = getKeysForPermissions(apiKey, workspaceName, requiredPermissions);
        if (permissionKeys.isEmpty()) {
            return Optional.empty();
        }
        RBucketsReactive buckets = redissonClient.getBuckets();
        Map<String, Object> bucketMap = buckets.get(permissionKeys.toArray(new String[0])).block();
        if (bucketMap == null || bucketMap.size() != permissionKeys.size()) {
            return Optional.empty();
        }
        List<String> userNames = permissionKeys.stream()
                .map(bucketMap::get)
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
        if (userNames.size() != permissionKeys.size()) {
            return Optional.empty();
        }
        return Optional.of(userNames.getFirst());
    }

    /**
     * Queues batch operations by invoking batch methods only without subscribing to their Monos
     * before {@code batch.execute()}, otherwise the call would deadlock (operation Monos complete on execute).
     */
    @Override
    public void cache(
            @NonNull String apiKey,
            @NonNull String requestWorkspaceName,
            List<String> requiredPermissions,
            @NonNull String userName,
            @NonNull String workspaceId,
            String resolvedWorkspaceName,
            List<Quota> quotas) {
        List<String> permissionKeys = getKeysForPermissions(apiKey, requestWorkspaceName, requiredPermissions);

        Duration ttl = Duration.ofSeconds(ttlInSeconds);
        String workspaceMetadataKey = getWorkspaceMetadataKey(requestWorkspaceName);
        Map<String, String> entry = Map.of(
                WORKSPACE_ID_KEY, workspaceId,
                QUOTAS_KEY, JsonUtils.writeValueAsString(Optional.ofNullable(quotas).orElse(List.of())));

        RBatchReactive batch = redissonClient.createBatch();
        RMapReactive<String, String> workspaceMap = batch.getMap(workspaceMetadataKey);
        for (String key : permissionKeys) {
            batch.getBucket(key).set(userName, ttl);
        }
        workspaceMap.putAll(entry);
        workspaceMap.expire(ttl);
        batch.execute().block();
    }

    private List<String> getKeysForPermissions(String apiKey, String workspaceName, List<String> requiredPermissions) {
        if (CollectionUtils.isEmpty(requiredPermissions)) {
            return List.of(getPermissionKey(apiKey, workspaceName, StringUtils.EMPTY));
        }
        return requiredPermissions.stream()
                .map(permission -> getPermissionKey(apiKey, workspaceName, permission))
                .toList();
    }

    private String getPermissionKey(String apiKey, String workspaceName, String permissionName) {
        return USER_ACCESS_KEY_FORMAT.formatted(apiKey, workspaceName, permissionName);
    }

    private String getWorkspaceMetadataKey(String workspaceName) {
        return WORKSPACE_METADATA_KEY_FORMAT.formatted(workspaceName);
    }

    private List<Quota> getQuotas(Map<String, String> redisMap) {
        var rawQuotas = Optional.ofNullable(redisMap.get(QUOTAS_KEY)).orElse("[]");
        return JsonUtils.readCollectionValue(rawQuotas, List.class, Quota.class);
    }

    @Builder(toBuilder = true)
    private record WorkspaceMetadataCache(String workspaceId, List<Quota> quotas) {
    }

}
