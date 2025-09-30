package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.Data;
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

    private static final String BUCKET_KEY_PREFIX = "alert_bucket:";
    private static final String EVENT_IDS_KEY = "eventIds";
    private static final String FIRST_SEEN_KEY = "firstSeen";
    private static final String WINDOW_SIZE_KEY = "windowSize";

    private final @NonNull RedissonReactiveClient redissonClient;
    private final @NonNull WebhookConfig webhookConfig;

    /**
     * Adds an event to an alert bucket.
     * If this is the first event for this alert+event type combination,
     * the current timestamp and debouncing window size are stored.
     * This ensures that configuration changes do not affect existing buckets.
     *
     * @param alertId the alert ID
     * @param eventType the event type
     * @param eventId the event ID to add
     * @return Mono that completes when the event is added
     */
    public Mono<Void> addEventToBucket(
            @NonNull UUID alertId,
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
                                    // First event in bucket - store timestamp, window size, and set TTL
                                    String timestamp = String.valueOf(Instant.now().toEpochMilli());
                                    String windowSize = String.valueOf(currentWindowSizeMillis);

                                    log.debug(
                                            "First event in bucket '{}', storing timestamp: '{}' and windowSize: '{}'ms",
                                            bucketKey, timestamp, windowSize);

                                    return bucket.put(FIRST_SEEN_KEY, timestamp)
                                            .then(bucket.put(WINDOW_SIZE_KEY, windowSize))
                                            .then(bucket.expire(java.time.Duration.ofMillis(
                                                    webhookConfig.getDebouncing().getBucketTtl().toMilliseconds())))
                                            .then();
                                } else {
                                    // Subsequent event - keep original timestamp, window size, and TTL
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
     * Retrieves all bucket keys that are ready to be processed.
     * A bucket is ready if (now - firstSeen) >= bucket's stored window size.
     * This ensures that configuration changes do not affect existing buckets:
     * - Old buckets continue to use their original window size
     * - New buckets created after config change use the new window size
     *
     * @return Flux of bucket keys ready for processing
     */
    public Flux<String> getBucketsReadyToProcess() {
        Instant now = Instant.now();
        String pattern = BUCKET_KEY_PREFIX + "*";

        log.debug("Checking for buckets ready to process");

        return redissonClient.getKeys().getKeysByPattern(pattern)
                .flatMap(bucketKey -> {
                    RMapReactive<String, String> bucket = redissonClient.getMap(bucketKey);

                    // Get both firstSeen and windowSize from the bucket
                    return Mono.zip(
                            bucket.get(FIRST_SEEN_KEY).map(Long::parseLong),
                            bucket.get(WINDOW_SIZE_KEY).map(Long::parseLong))
                            .filter(tuple -> {
                                long firstSeenMillis = tuple.getT1();
                                long bucketWindowMillis = tuple.getT2();
                                long elapsedMillis = now.toEpochMilli() - firstSeenMillis;
                                boolean ready = elapsedMillis >= bucketWindowMillis;

                                if (ready) {
                                    log.debug("Bucket '{}' is ready (elapsed: '{}ms' >= stored window: '{}ms')",
                                            bucketKey, elapsedMillis, bucketWindowMillis);
                                } else {
                                    log.trace("Bucket '{}' not ready yet (elapsed: '{}ms' < stored window: '{}ms')",
                                            bucketKey, elapsedMillis, bucketWindowMillis);
                                }

                                return ready;
                            })
                            .map(__ -> bucketKey)
                            .switchIfEmpty(Mono.empty());
                })
                .doOnComplete(() -> log.debug("Finished checking for buckets ready to process"))
                .doOnError(error -> log.error("Failed to check for buckets: {}", error.getMessage(), error));
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
                .map(json -> {
                    Set<String> eventIds = JsonUtils.readCollectionValue(json, Set.class, String.class);
                    return (Set<String>) new HashSet<>(eventIds);
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
                bucket.get(WINDOW_SIZE_KEY))
                .map(tuple -> {
                    String eventIdsJson = tuple.getT1();
                    String firstSeenStr = tuple.getT2();
                    String windowSizeStr = tuple.getT3();

                    Set<String> eventIds = JsonUtils.readCollectionValue(eventIdsJson, Set.class, String.class);
                    long firstSeen = Long.parseLong(firstSeenStr);
                    long windowSize = Long.parseLong(windowSizeStr);

                    return BucketData.builder()
                            .eventIds(new HashSet<>(eventIds))
                            .firstSeen(firstSeen)
                            .windowSize(windowSize)
                            .build();
                })
                .doOnSuccess(
                        data -> log.debug("Retrieved bucket data for '{}': {} events, firstSeen={}, windowSize={}ms",
                                bucketKey, data.getEventIds().size(), data.getFirstSeen(), data.getWindowSize()))
                .doOnError(error -> log.error("Failed to retrieve bucket data for '{}': {}",
                        bucketKey, error.getMessage(), error));
    }

    /**
     * Data class to hold complete bucket information.
     */
    @Data
    @Builder
    public static class BucketData {
        private Set<String> eventIds;
        private long firstSeen;
        private long windowSize;
    }

    /**
     * Deletes a processed bucket from Redis.
     *
     * @param bucketKey the bucket key to delete
     * @return Mono that completes when the bucket is deleted
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
                .then();
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
