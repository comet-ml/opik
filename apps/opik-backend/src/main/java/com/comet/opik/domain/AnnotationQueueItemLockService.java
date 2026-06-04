package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueueItemLock;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ImplementedBy(AnnotationQueueItemLockServiceImpl.class)
public interface AnnotationQueueItemLockService {

    Mono<AnnotationQueueItemLock.LockResponse> tryLock(
            @NonNull String workspaceId,
            @NonNull UUID queueId,
            @NonNull UUID itemId,
            @NonNull String userName,
            int annotatorsPerItem,
            int scoredCount,
            int lockTimeoutMinutes);

    Mono<Map<UUID, AnnotationQueueItemLock.ItemLockInfo>> getLocksForQueue(
            @NonNull String workspaceId,
            @NonNull UUID queueId);
}

@Singleton
@Slf4j
class AnnotationQueueItemLockServiceImpl implements AnnotationQueueItemLockService {

    private static final String HASH_KEY_PREFIX = "aq:locks:";
    private static final String LOCK_NAME = "aq_claim";

    private final RedissonReactiveClient redisClient;
    private final LockService lockService;

    @Inject
    AnnotationQueueItemLockServiceImpl(
            @NonNull RedissonReactiveClient redisClient,
            @NonNull LockService lockService) {
        this.redisClient = redisClient;
        this.lockService = lockService;
    }

    @Override
    public Mono<AnnotationQueueItemLock.LockResponse> tryLock(
            @NonNull String workspaceId,
            @NonNull UUID queueId,
            @NonNull UUID itemId,
            @NonNull String userName,
            int annotatorsPerItem,
            int scoredCount,
            int lockTimeoutMinutes) {

        String hashKey = buildHashKey(workspaceId, queueId);
        String field = itemId + ":" + userName;
        String itemPrefix = itemId + ":";
        long now = System.currentTimeMillis();
        long expiryMs = now + (long) lockTimeoutMinutes * 60 * 1000;

        // Distributed mutex on the item ensures only one tryLock runs at a time per item
        return lockService.executeWithLock(
                new LockService.Lock(itemId, LOCK_NAME),
                Mono.defer(() -> {
                    var map = redisClient.<String, String>getMap(hashKey, StringCodec.INSTANCE);
                    return map.readAllMap()
                            .flatMap(entries -> {
                                // User already holds a lock on this item — refresh expiry (heartbeat)
                                String existing = entries.get(field);
                                if (existing != null && Long.parseLong(existing) > now) {
                                    return map.put(field, String.valueOf(expiryMs))
                                            .thenReturn(true);
                                }

                                // Count active (non-expired) locks for this item
                                int activeLocks = 0;
                                for (var entry : entries.entrySet()) {
                                    if (entry.getKey().startsWith(itemPrefix)
                                            && Long.parseLong(entry.getValue()) > now) {
                                        activeLocks++;
                                    }
                                }

                                // All annotator slots taken — lock not acquired
                                if (scoredCount + activeLocks >= annotatorsPerItem) {
                                    return Mono.just(false);
                                }

                                // Slot available — create the lock
                                return map.put(field, String.valueOf(expiryMs))
                                        .thenReturn(true);
                            });
                }))
                .map(acquired -> AnnotationQueueItemLock.LockResponse.builder()
                        .acquired(acquired)
                        .itemId(itemId)
                        .lockedBy(userName)
                        .expiresAt(acquired ? Instant.ofEpochMilli(expiryMs) : null)
                        .build());
    }

    @Override
    public Mono<Map<UUID, AnnotationQueueItemLock.ItemLockInfo>> getLocksForQueue(
            @NonNull String workspaceId,
            @NonNull UUID queueId) {

        String hashKey = buildHashKey(workspaceId, queueId);
        long now = System.currentTimeMillis();

        return redisClient.<String, String>getMap(hashKey, StringCodec.INSTANCE)
                .readAllMap()
                .map(entries -> {
                    Map<UUID, List<String>> activeLocksByItem = new HashMap<>();
                    List<String> expiredFields = new ArrayList<>();

                    for (var entry : entries.entrySet()) {
                        String field = entry.getKey();
                        long expiry = Long.parseLong(entry.getValue());

                        if (expiry <= now) {
                            expiredFields.add(field);
                            continue;
                        }

                        int separatorIdx = field.lastIndexOf(':');
                        UUID itemId = UUID.fromString(field.substring(0, separatorIdx));
                        String lockedByUser = field.substring(separatorIdx + 1);

                        activeLocksByItem
                                .computeIfAbsent(itemId, k -> new ArrayList<>())
                                .add(lockedByUser);
                    }

                    Map<UUID, AnnotationQueueItemLock.ItemLockInfo> result = new HashMap<>();
                    for (var entry : activeLocksByItem.entrySet()) {
                        List<String> lockedBy = entry.getValue();
                        result.put(entry.getKey(), AnnotationQueueItemLock.ItemLockInfo.builder()
                                .activeLocks(lockedBy.size())
                                .lockedBy(lockedBy)
                                .build());
                    }

                    // Lazy cleanup of all expired entries
                    if (!expiredFields.isEmpty()) {
                        redisClient.<String, String>getMap(hashKey, StringCodec.INSTANCE)
                                .fastRemove(expiredFields.toArray(String[]::new))
                                .subscribe();
                    }

                    return result;
                });
    }

    private String buildHashKey(String workspaceId, UUID queueId) {
        return HASH_KEY_PREFIX + workspaceId + ":" + queueId;
    }
}
