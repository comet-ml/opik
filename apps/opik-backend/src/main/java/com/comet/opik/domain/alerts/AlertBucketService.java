package com.comet.opik.domain.alerts;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service responsible for managing alert event buckets in Redis.
 * Buckets aggregate events by alert ID and event type for debouncing.
 *
 * This service is called by:
 * - Alert evaluation logic: to add events to buckets
 * - AlertJob: to retrieve, process, and delete buckets
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AlertBucketService {

    private static final String BUCKET_KEY_PREFIX = "opik:alert_bucket:";
    private static final String BUCKET_INDEX_KEY = "opik:alert_bucket_index"; // ZSET: score=readyTimestamp, value=bucketKey
    private static final String EVENT_IDS_KEY = "eventIds";
    private static final String PAYLOADS_KEY = "payloads";
    private static final String USER_NAMES_KEY = "userNames";
    private static final String FIRST_SEEN_KEY = "firstSeen";
    private static final String WINDOW_SIZE_KEY = "windowSize";
    private static final String WORKSPACE_ID_KEY = "workspaceId";
    private static final String WORKSPACE_NAME_KEY = "workspaceName";

    private final @NonNull RedissonReactiveClient redissonClient;
    private final @NonNull WebhookConfig webhookConfig;

    /**
     * Adds an event to an alert bucket.
     * If this is the first event for this alert+event type combination,
     * the current timestamp, debouncing window size, workspace ID, and workspace name are stored.
     * This ensures that configuration changes do not affect existing buckets.
     *
     * @param alertId the alert ID
     * @param workspaceId the workspace ID for the alert
     * @param workspaceName the workspace name for the alert
     * @param eventType the event type
     * @param eventId the event ID to add
     * @param payload the event payload to add
     * @param userName the user name associated with the event
     * @return Mono that completes when the event is added
     */
    public Mono<Void> addEventToBucket(
            @NonNull UUID alertId,
            @NonNull String workspaceId,
            @NonNull String workspaceName,
            @NonNull AlertEventType eventType,
            @NonNull String eventId,
            @NonNull String payload,
            @NonNull String userName) {

        String bucketKey = generateBucketKey(alertId, eventType);
        RMapReactive<String, String> bucket = redissonClient.getMap(bucketKey);
        long currentWindowSizeMillis = webhookConfig.getDebouncing().getWindowSize().toMilliseconds();

        log.debug("Adding event '{}' with payload to bucket '{}' for alert '{}' and type '{}'",
                eventId, bucketKey, alertId, eventType);

        return Mono.zip(
                bucket.get(EVENT_IDS_KEY).defaultIfEmpty("[]"),
                bucket.get(PAYLOADS_KEY).defaultIfEmpty("[]"),
                bucket.get(USER_NAMES_KEY).defaultIfEmpty("[]"))
                .map(tuple -> {
                    List<String> eventIds = JsonUtils.readCollectionValue(tuple.getT1(), List.class, String.class);
                    List<String> payloads = JsonUtils.readCollectionValue(tuple.getT2(), List.class, String.class);
                    List<String> userNames = JsonUtils.readCollectionValue(tuple.getT3(), List.class, String.class);
                    return new Object[]{eventIds, payloads, userNames};
                })
                .flatMap(sets -> {
                    @SuppressWarnings("unchecked")
                    List<String> eventIds = (List<String>) sets[0];
                    @SuppressWarnings("unchecked")
                    List<String> payloads = (List<String>) sets[1];
                    @SuppressWarnings("unchecked")
                    List<String> userNames = (List<String>) sets[2];

                    eventIds.add(eventId);
                    payloads.add(payload);
                    userNames.add(userName);

                    String updatedEventIdsJson = JsonUtils.writeValueAsString(eventIds);
                    String updatedPayloadsJson = JsonUtils.writeValueAsString(payloads);
                    String updatedUserNamesJson = JsonUtils.writeValueAsString(userNames);

                    // Store updated event IDs, payloads, and user names
                    return bucket.put(EVENT_IDS_KEY, updatedEventIdsJson)
                            .then(bucket.put(PAYLOADS_KEY, updatedPayloadsJson))
                            .then(bucket.put(USER_NAMES_KEY, updatedUserNamesJson))
                            .then(bucket.get(FIRST_SEEN_KEY).defaultIfEmpty(""))
                            .flatMap(firstSeen -> {
                                if (firstSeen == null || firstSeen.isEmpty()) {
                                    // First event in bucket - store timestamp, window size, workspace ID, workspace name, and set TTL
                                    long firstSeenMillis = Instant.now().toEpochMilli();
                                    String timestamp = String.valueOf(firstSeenMillis);
                                    String windowSize = String.valueOf(currentWindowSizeMillis);

                                    // Calculate when this bucket will be ready to process
                                    double readyTimestamp = firstSeenMillis + currentWindowSizeMillis;

                                    log.debug(
                                            "First event in bucket '{}', storing timestamp: '{}', windowSize: '{}'ms, readyTimestamp: '{}', workspaceId: '{}', and workspaceName: '{}'",
                                            bucketKey, timestamp, windowSize, readyTimestamp, workspaceId,
                                            workspaceName);

                                    return bucket.put(FIRST_SEEN_KEY, timestamp)
                                            .then(bucket.put(WINDOW_SIZE_KEY, windowSize))
                                            .then(bucket.put(WORKSPACE_ID_KEY, workspaceId))
                                            .then(bucket.put(WORKSPACE_NAME_KEY, workspaceName))
                                            .then(bucket.expire(java.time.Duration.ofMillis(
                                                    webhookConfig.getDebouncing().getBucketTtl().toMilliseconds())))
                                            .then(addBucketToIndex(bucketKey, readyTimestamp))
                                            .then();
                                } else {
                                    // Subsequent event - keep original timestamp, window size, workspace ID, workspace name, and TTL
                                    log.debug(
                                            "Adding to existing bucket '{}', keeping original timestamp, windowSize, and TTL",
                                            bucketKey);
                                    return Mono.empty();
                                }
                            });
                })
                .doOnSuccess(__ -> log.info("Successfully added event '{}' to bucket '{}'", eventId, bucketKey))
                .doOnError(error -> log.error("Failed to add event '{}' to bucket '{}': {}",
                        eventId, bucketKey, error.getMessage(), error))
                .then();
    }

    /**
     * Adds a bucket key to the index with its ready timestamp as the score.
     * This allows efficient retrieval of buckets ready to be processed.
     * Also sets/renews the TTL on the index to 2x the bucket TTL duration.
     *
     * Note: If the index doesn't exist yet (first bucket or after expiration),
     * the add() operation will automatically create it, and expire() will set its TTL.
     *
     * @param bucketKey the bucket key
     * @param readyTimestamp the timestamp when the bucket will be ready (firstSeen + windowSize)
     * @return Mono that completes when the bucket is added to the index
     */
    private Mono<Void> addBucketToIndex(String bucketKey, double readyTimestamp) {
        var index = redissonClient.getScoredSortedSet(BUCKET_INDEX_KEY);
        long indexTtlMillis = webhookConfig.getDebouncing().getBucketTtl().toMilliseconds() * 2;

        return index.add(readyTimestamp, bucketKey)
                .flatMap(added -> {
                    if (added) {
                        log.debug("Added bucket '{}' to index with ready timestamp '{}'", bucketKey, readyTimestamp);
                    } else {
                        log.debug("Bucket '{}' already exists in index", bucketKey);
                    }
                    // Set/renew TTL on the index to keep it alive as long as there's activity
                    // This works whether the index was just created or already existed
                    return index.expire(java.time.Duration.ofMillis(indexTtlMillis));
                })
                .doOnError(error -> log.error("Failed to add bucket '{}' to index: {}",
                        bucketKey, error.getMessage(), error))
                .then();
    }

    /**
     * Removes a bucket key from the index.
     * Also renews the TTL on the index to 2x the bucket TTL duration.
     *
     * @param bucketKey the bucket key to remove
     * @return Mono that completes when the bucket is removed from the index
     */
    private Mono<Void> removeBucketFromIndex(String bucketKey) {
        var index = redissonClient.getScoredSortedSet(BUCKET_INDEX_KEY);
        long indexTtlMillis = webhookConfig.getDebouncing().getBucketTtl().toMilliseconds() * 2;

        return index.remove(bucketKey)
                .flatMap(removed -> {
                    if (removed) {
                        log.debug("Removed bucket '{}' from index", bucketKey);
                    } else {
                        log.debug("Bucket '{}' was not in index or already removed", bucketKey);
                    }
                    // Renew TTL on the index to keep it alive as long as there's activity
                    return index.expire(java.time.Duration.ofMillis(indexTtlMillis));
                })
                .doOnError(error -> log.error("Failed to remove bucket '{}' from index: {}",
                        bucketKey, error.getMessage(), error))
                .then();
    }

    /**
     * Retrieves all bucket keys that are ready to be processed using an indexed lookup.
     * Uses a Redis Sorted Set (ZSET) to efficiently query buckets by their ready timestamp.
     * This is O(log(N) + M) where N is total buckets and M is ready buckets,
     * much better than the previous O(N) full keyspace scan.
     *
     * A bucket is ready if its stored ready timestamp (firstSeen + windowSize) <= now.
     * This ensures that configuration changes do not affect existing buckets:
     * - Old buckets continue to use their original window size
     * - New buckets created after config change use the new window size
     *
     * The index itself has a TTL (2x bucket TTL) that's renewed on each add/remove operation.
     * If there's no activity, the entire index expires automatically.
     *
     * @return Flux of bucket keys ready for processing
     */
    public Flux<String> getBucketsReadyToProcess() {
        long nowMillis = Instant.now().toEpochMilli();

        log.debug("Checking for buckets ready to process using indexed lookup (up to timestamp: '{}')", nowMillis);

        // Query the sorted set for all buckets with score <= now
        // This is O(log(N) + M) instead of O(N) for scanning all keys
        return redissonClient.getScoredSortedSet(BUCKET_INDEX_KEY)
                .valueRange(Double.NEGATIVE_INFINITY, true, nowMillis, true)
                .flatMapMany(collection -> Flux.fromIterable(collection)
                        .map(Object::toString))
                .doOnComplete(() -> log.debug("Finished checking for buckets ready to process"))
                .doOnError(
                        error -> log.error("Failed to check for buckets using index: {}", error.getMessage(), error));
    }

    /**
     * Retrieves complete bucket data including event IDs, payloads, user names, firstSeen timestamp, window size, workspace ID, and workspace name.
     *
     * @param bucketKey the bucket key
     * @return Mono containing BucketData with all bucket information
     */
    public Mono<BucketData> getBucketData(@NonNull String bucketKey) {
        RMapReactive<String, String> bucket = redissonClient.getMap(bucketKey);

        return Mono.zip(
                bucket.get(EVENT_IDS_KEY).defaultIfEmpty("[]"),
                bucket.get(PAYLOADS_KEY).defaultIfEmpty("[]"),
                bucket.get(USER_NAMES_KEY).defaultIfEmpty("[]"),
                bucket.get(FIRST_SEEN_KEY),
                bucket.get(WINDOW_SIZE_KEY),
                bucket.get(WORKSPACE_ID_KEY),
                bucket.get(WORKSPACE_NAME_KEY))
                .flatMap(tuple -> Mono.fromCallable(() -> {
                    String eventIdsJson = tuple.getT1();
                    String payloadsJson = tuple.getT2();
                    String userNamesJson = tuple.getT3();
                    String firstSeenStr = tuple.getT4();
                    String windowSizeStr = tuple.getT5();
                    String workspaceId = tuple.getT6();
                    String workspaceName = tuple.getT7();

                    List<String> eventIds = JsonUtils.readCollectionValue(eventIdsJson, List.class, String.class);
                    List<String> payloads = JsonUtils.readCollectionValue(payloadsJson, List.class, String.class);
                    List<String> userNames = JsonUtils.readCollectionValue(userNamesJson, List.class, String.class);
                    long firstSeen = Long.parseLong(firstSeenStr);
                    long windowSize = Long.parseLong(windowSizeStr);

                    return BucketData.builder()
                            .eventIds(new ArrayList<>(eventIds))
                            .payloads(new ArrayList<>(payloads))
                            .userNames(new ArrayList<>(userNames))
                            .firstSeen(firstSeen)
                            .windowSize(windowSize)
                            .workspaceId(workspaceId)
                            .workspaceName(workspaceName)
                            .build();
                }))
                .doOnSuccess(
                        data -> log.debug(
                                "Retrieved bucket data for '{}': {} events, {} payloads, {} userNames, firstSeen={}, windowSize={}ms, workspaceId='{}', workspaceName='{}'",
                                bucketKey, data.eventIds().size(), data.payloads().size(), data.userNames().size(),
                                data.firstSeen(), data.windowSize(), data.workspaceId(), data.workspaceName()))
                .doOnError(error -> log.error("Failed to retrieve bucket data for '{}': {}",
                        bucketKey, error.getMessage(), error));
    }

    /**
     * Data class to hold complete bucket information.
     */
    @Builder
    public record BucketData(@NonNull List<String> eventIds, @NonNull List<String> payloads,
            @NonNull List<String> userNames, long firstSeen, long windowSize,
            @NonNull String workspaceId, @NonNull String workspaceName) {
    }

    /**
     * Deletes a processed bucket from Redis and removes it from the index.
     *
     * @param bucketKey the bucket key to delete
     * @return Mono that completes when the bucket is deleted and removed from index
     */
    public Mono<Void> deleteBucket(@NonNull String bucketKey) {
        return redissonClient.getBucket(bucketKey)
                .delete()
                .doOnSuccess(deleted -> {
                    if (deleted) {
                        log.info("Successfully deleted processed bucket: '{}'", bucketKey);
                    } else {
                        log.warn("Bucket '{}' was already deleted or did not exist", bucketKey);
                    }
                })
                .doOnError(error -> log.error("Failed to delete bucket '{}': {}",
                        bucketKey, error.getMessage(), error))
                .then(removeBucketFromIndex(bucketKey));
    }

    /**
     * Parses alert ID and event type from a bucket key.
     *
     * @param bucketKey the bucket key in format "alert_bucket:{alertId}:{eventType}"
     * @return array with [alertId, eventType]
     */
    public String[] parseBucketKey(@NonNull String bucketKey) {
        String withoutPrefix = bucketKey.substring(BUCKET_KEY_PREFIX.length());
        return withoutPrefix.split(":", 2);
    }

    /**
     * Generates a bucket key for a given alert ID and event type.
     *
     * @param alertId the alert ID
     * @param eventType the event type
     * @return the bucket key in format "alert_bucket:{alertId}:{eventType}"
     */
    private String generateBucketKey(@NonNull UUID alertId, @NonNull AlertEventType eventType) {
        return BUCKET_KEY_PREFIX + alertId + ":" + eventType.getValue();
    }
}
