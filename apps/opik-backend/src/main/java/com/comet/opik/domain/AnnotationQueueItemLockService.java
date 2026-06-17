package com.comet.opik.domain;

import com.comet.opik.api.ItemLockInfo;
import com.comet.opik.api.LockResponse;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ImplementedBy(AnnotationQueueItemLockServiceImpl.class)
public interface AnnotationQueueItemLockService {

    Mono<LockResponse> tryLock(
            String workspaceId,
            UUID queueId,
            UUID itemId,
            String userName,
            int annotatorsPerItem,
            int scoredCount,
            int lockTimeoutSeconds);

    Mono<Map<UUID, ItemLockInfo>> getLocksForQueue(
            String workspaceId,
            UUID queueId);

    Mono<Void> updateCapacity(
            String workspaceId,
            UUID queueId,
            int delta);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AnnotationQueueItemLockServiceImpl implements AnnotationQueueItemLockService {

    private static final String USER_MAP_PREFIX = "aq:locks:";
    private static final String SLOT_KEY_PREFIX = "aq:slots:";
    private static final String SEPARATOR = ":";

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull LockService lockService;

    @Override
    public Mono<LockResponse> tryLock(
            @NonNull String workspaceId,
            @NonNull UUID queueId,
            @NonNull UUID itemId,
            @NonNull String userName,
            int annotatorsPerItem,
            int scoredCount,
            int lockTimeoutSeconds) {

        long now = System.currentTimeMillis();
        long expiryMs = now + (long) lockTimeoutSeconds * 1000;

        if (scoredCount >= annotatorsPerItem) {
            return Mono.just(buildResponse(false, itemId, userName, expiryMs));
        }

        var slotLock = new LockService.Lock(buildSlotKey(workspaceId, queueId, itemId));
        Duration lease = Duration.ofSeconds(lockTimeoutSeconds);
        String field = field(itemId, userName);
        int effectiveCapacity = annotatorsPerItem - scoredCount;
        var userMap = userMap(workspaceId, queueId);

        log.debug("tryLock item '{}' for user '{}' in queue '{}' (effectiveCapacity={})",
                itemId, userName, queueId, effectiveCapacity);

        // Heartbeat: if user already holds a permit, refresh its lease
        Mono<Boolean> heartbeat = userMap.get(field)
                .flatMap(existing -> {
                    String existingPermitId = extractPermitId(existing);
                    long existingExpiry = extractExpiry(existing);
                    if (existingExpiry <= now) {
                        return Mono.empty();
                    }
                    return lockService.refreshSlot(slotLock, existingPermitId, lease)
                            .flatMap(refreshed -> refreshed
                                    ? userMap.fastPut(field, packValue(existingPermitId, expiryMs))
                                            .then(userMap.expire(lease))
                                            .thenReturn(true)
                                    : Mono.empty());
                });

        return heartbeat
                .switchIfEmpty(Mono.defer(() ->
                // Fresh acquire: atomic semaphore tryAcquire, then store permitId in map
                lockService.tryAcquireSlot(slotLock, effectiveCapacity, lease)
                        .flatMap(newPermitId -> userMap
                                .fastPutIfAbsent(field, packValue(newPermitId, expiryMs))
                                .flatMap(stored -> stored
                                        ? userMap.expire(lease).thenReturn(true)
                                        : lockService.releaseSlot(slotLock, newPermitId).thenReturn(true)))
                        .defaultIfEmpty(false)))
                .map(acquired -> buildResponse(acquired, itemId, userName, expiryMs));
    }

    @Override
    public Mono<Map<UUID, ItemLockInfo>> getLocksForQueue(
            @NonNull String workspaceId,
            @NonNull UUID queueId) {

        long now = System.currentTimeMillis();
        var userMap = userMap(workspaceId, queueId);

        return userMap
                .readAllMap()
                .flatMap(entries -> {
                    Map<UUID, List<String>> byItem = new HashMap<>();
                    List<String> expiredFields = new ArrayList<>();

                    for (var entry : entries.entrySet()) {
                        long expiry = extractExpiry(entry.getValue());
                        if (expiry <= now) {
                            expiredFields.add(entry.getKey());
                            continue;
                        }
                        UUID itemId = extractItemId(entry.getKey());
                        String user = extractUserName(entry.getKey());
                        byItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(user);
                    }

                    Map<UUID, ItemLockInfo> result = new HashMap<>();
                    for (var entry : byItem.entrySet()) {
                        List<String> users = entry.getValue();
                        result.put(entry.getKey(), ItemLockInfo.builder()
                                .activeLocks(users.size())
                                .lockedBy(users)
                                .build());
                    }

                    // Lazy cleanup of expired entries
                    if (!expiredFields.isEmpty()) {
                        return userMap
                                .fastRemove(expiredFields.toArray(String[]::new))
                                .thenReturn(result);
                    }

                    return Mono.just(result);
                });
    }

    @Override
    public Mono<Void> updateCapacity(
            @NonNull String workspaceId,
            @NonNull UUID queueId,
            int delta) {

        if (delta == 0) {
            return Mono.empty();
        }

        long now = System.currentTimeMillis();
        log.debug("Updating capacity for queue '{}' by delta={}", queueId, delta);

        return userMap(workspaceId, queueId)
                .readAllMap()
                .flatMap(entries -> {
                    var itemIds = entries.entrySet().stream()
                            .filter(e -> extractExpiry(e.getValue()) > now)
                            .map(e -> extractItemId(e.getKey()))
                            .distinct()
                            .toList();

                    if (itemIds.isEmpty()) {
                        return Mono.empty();
                    }

                    return Flux.fromIterable(itemIds)
                            .flatMap(itemId -> {
                                var slotLock = new LockService.Lock(buildSlotKey(workspaceId, queueId, itemId));
                                return lockService.addSlotPermits(slotLock, delta);
                            })
                            .then();
                });
    }

    private RMapReactive<String, String> userMap(String workspaceId, UUID queueId) {
        return redisClient.getMap(USER_MAP_PREFIX + workspaceId + ":" + queueId, StringCodec.INSTANCE);
    }

    private String buildSlotKey(String workspaceId, UUID queueId, UUID itemId) {
        return SLOT_KEY_PREFIX + workspaceId + ":" + queueId + ":" + itemId;
    }

    private String field(UUID itemId, String userName) {
        return itemId + SEPARATOR + userName;
    }

    private String packValue(String permitId, long expiryMs) {
        return permitId + SEPARATOR + expiryMs;
    }

    private String extractPermitId(String value) {
        return value.substring(0, value.lastIndexOf(SEPARATOR));
    }

    private long extractExpiry(String value) {
        return Long.parseLong(value.substring(value.lastIndexOf(SEPARATOR) + 1));
    }

    private UUID extractItemId(String field) {
        int separatorIdx = field.lastIndexOf(SEPARATOR);
        return UUID.fromString(field.substring(0, separatorIdx));
    }

    private String extractUserName(String field) {
        return field.substring(field.lastIndexOf(SEPARATOR) + 1);
    }

    private LockResponse buildResponse(boolean acquired, UUID itemId, String userName,
            long expiryMs) {
        return LockResponse.builder()
                .acquired(acquired)
                .itemId(itemId)
                .lockedBy(userName)
                .expiresAt(acquired ? Instant.ofEpochMilli(expiryMs) : null)
                .build();
    }
}
