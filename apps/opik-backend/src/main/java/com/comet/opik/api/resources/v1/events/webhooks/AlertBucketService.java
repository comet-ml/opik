package com.comet.opik.api.resources.v1.events.webhooks;

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
import java.util.HashSet;
import java.util.Set;
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
    private static final String FIRST_SEEN_KEY = "firstSeen";
    private static final String WINDOW_SIZE_KEY = "windowSize";
    private static final String WORKSPACE_ID_KEY = "workspaceId";

    private final @NonNull RedissonReactiveClient redissonClient;
    private final @NonNull WebhookConfig webhookConfig;

    /**
     * Adds an event to an alert bucket.
     * If this is the first event for this alert+event type combination,
     * the current timestamp, debouncing window size, and workspace ID are stored.
     * This ensures that configuration changes do not affect existing buckets.
     *
     * @param alertId the alert ID
     * @param workspaceId the workspace ID for the alert
     * @param eventType the event type
     * @param eventId the event ID to add
     * @return Mono that completes when the event is added
     */
    public Mono<Void> addEventToBucket(
            @NonNull UUID alertId,
            @NonNull String workspaceId,
            @NonNull AlertEventType eventType,
            @NonNull String eventId) {

        String bucketKey = generateBucketKey(alertId, eventType);
        RMapReactive<String, String> bucket = redissonClient.getMap(bucketKey);
        long currentWindowSizeMillis = webhookConfig.getDebouncing().getWindowSize().toMilliseconds();

        log.debug("Adding event '{}' to bucket '{}' for alert '{}' and type '{}'",
                eventId, bucketKey, alertId, eventType);

        return bucket.get(EVENT_IDS_KEY)
                .defaultIfEmpty("[]")
                .map(json -> {
                    Set<String> eventIds = JsonUtils.readCollectionValue(json, Set.class, String.class);
                    return new HashSet<>(eventIds);
                })
                .flatMap(eventIds -> {
                    eventIds.add(eventId);
                    String updatedJson = JsonUtils.writeValueAsString(eventIds);

                    // Store updated event IDs
                    return bucket.put(EVENT_IDS_KEY, updatedJson)
                            .then(bucket.get(FIRST_SEEN_KEY).defaultIfEmpty(""))
                            .flatMap(firstSeen -> {
                                if (firstSeen == null || firstSeen.isEmpty()) {
                                    // First event in bucket - store timestamp, window size, workspace ID, and set TTL
                                    long firstSeenMillis = Instant.now().toEpochMilli();
                                    String timestamp = String.valueOf(firstSeenMillis);
                                    String windowSize = String.valueOf(currentWindowSizeMillis);

                                    // Calculate when this bucket will be ready to process
                                    double readyTimestamp = firstSeenMillis + currentWindowSizeMillis;

                                    log.debug(
                                            "First event in bucket '{}', storing timestamp: '{}', windowSize: '{}'ms, readyTimestamp: '{}', and workspaceId: '{}'",
                                            bucketKey, timestamp, windowSize, readyTimestamp, workspaceId);

                                    return bucket.put(FIRST_SEEN_KEY, timestamp)
                                            .then(bucket.put(WINDOW_SIZE_KEY, windowSize))
                                            .then(bucket.put(WORKSPACE_ID_KEY, workspaceId))
                                            .then(bucket.expire(java.time.Duration.ofMillis(
                                                    webhookConfig.getDebouncing().getBucketTtl().toMilliseconds())))
                                            .then(addBucketToIndex(bucketKey, readyTimestamp))
                                            .then();
                                } else {
                                    // Subsequent event - keep original timestamp, window size, workspace ID, and TTL
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
     * Retrieves the bucket data (event IDs) for a given bucket key.
     *
     * @param bucketKey the bucket key
     * @return Mono containing the set of event IDs
     */
    public Mono<Set<String>> getBucketEventIds(@NonNull String bucketKey) {
        RMapReactive<String, String> bucket = redissonClient.getMap(bucketKey);

        return bucket.get(EVENT_IDS_KEY)
                .defaultIfEmpty("[]")
                .<Set<String>>map(json -> {
                    Set<String> eventIds = JsonUtils.readCollectionValue(json, Set.class, String.class);
                    return new HashSet<>(eventIds);
                })
                .doOnSuccess(eventIds -> log.debug("Retrieved {} events from bucket '{}'",
                        eventIds.size(), bucketKey))
                .doOnError(error -> log.error("Failed to retrieve event IDs from bucket '{}': {}",
                        bucketKey, error.getMessage(), error));
    }

    /**
     * Retrieves complete bucket data including event IDs, firstSeen timestamp, and window size.
     *
     * @param bucketKey the bucket key
     * @return Mono containing BucketData with all bucket information
     */
    public Mono<BucketData> getBucketData(@NonNull String bucketKey) {
        RMapReactive<String, String> bucket = redissonClient.getMap(bucketKey);

        return Mono.zip(
                bucket.get(EVENT_IDS_KEY).defaultIfEmpty("[]"),
                bucket.get(FIRST_SEEN_KEY),
                bucket.get(WINDOW_SIZE_KEY),
                bucket.get(WORKSPACE_ID_KEY))
                .flatMap(tuple -> Mono.fromCallable(() -> {
                    String eventIdsJson = tuple.getT1();
                    String firstSeenStr = tuple.getT2();
                    String windowSizeStr = tuple.getT3();
                    String workspaceId = tuple.getT4();

                    Set<String> eventIds = JsonUtils.readCollectionValue(eventIdsJson, Set.class, String.class);
                    long firstSeen = Long.parseLong(firstSeenStr);
                    long windowSize = Long.parseLong(windowSizeStr);

                    return BucketData.builder()
                            .eventIds(new HashSet<>(eventIds))
                            .firstSeen(firstSeen)
                            .windowSize(windowSize)
                            .workspaceId(workspaceId)
                            .build();
                }))
                .doOnSuccess(
                        data -> log.debug("Retrieved bucket data for '{}': {} events, firstSeen={}, windowSize={}ms",
                                bucketKey, data.eventIds().size(), data.firstSeen(), data.windowSize()))
                .doOnError(error -> log.error("Failed to retrieve bucket data for '{}': {}",
                        bucketKey, error.getMessage(), error));
    }

    /**
     * Data class to hold complete bucket information.
     */
    @Builder
    public record BucketData(Set<String> eventIds, long firstSeen, long windowSize, String workspaceId) {
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
