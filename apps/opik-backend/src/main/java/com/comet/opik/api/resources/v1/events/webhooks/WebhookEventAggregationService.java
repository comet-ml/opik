package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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

/**
 * Service responsible for aggregating webhook events with timestamp-based debouncing.
 * Events are stored with their first-seen timestamp and grouped by alert ID and event type.
 * A background job checks every 5 seconds if events are ready to be published.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class WebhookEventAggregationService {

    // Key format: "webhook_pending:{alertId}:{eventType}"
    private static final String PENDING_KEY_PREFIX = "webhook_pending:";
    
    // Stores event metadata: {eventId -> timestamp}
    private static final String EVENT_TIMESTAMP_KEY = "event_timestamps";

    private final @NonNull RedissonReactiveClient redissonClient;
    private final @NonNull WebhookConfig webhookConfig;

    /**
     * Aggregates a webhook event with timestamp tracking for debouncing.
     * If this is the first event for this alert+eventType combination,
     * stores the current timestamp as the "first seen" time.
     *
     * @param event the webhook event to aggregate
     * @return Mono that completes when the event is successfully added
     */
    public Mono<Void> aggregateEvent(@NonNull WebhookEvent<?> event) {
        String pendingKey = generatePendingKey(event);
        Instant now = Instant.now();

        log.debug("Aggregating event '{}' with pending key '{}'", event.getId(), pendingKey);

        RMapReactive<String, String> pendingEvents = redissonClient.getMap(pendingKey);

        return pendingEvents.get(EVENT_TIMESTAMP_KEY)
                .defaultIfEmpty(String.valueOf(now.toEpochMilli()))
                .flatMap(timestampStr -> {
                    // Get existing event IDs
                    return pendingEvents.get("eventIds")
                            .defaultIfEmpty("[]")
                            .map(json -> {
                                Set<String> eventIds = JsonUtils.readCollectionValue(json, Set.class, String.class);
                                return new HashSet<>(eventIds);
                            })
                            .doOnNext(eventIds -> eventIds.add(event.getId()))
                            .flatMap(eventIds -> {
                                // Store timestamp (only if first event) and updated event IDs
                                return pendingEvents.fastPut(EVENT_TIMESTAMP_KEY, timestampStr)
                                        .then(pendingEvents.fastPut("eventIds", JsonUtils.writeValueAsString(eventIds)))
                                        .then(pendingEvents.expire(webhookConfig.getDebouncing().getBucketTtl().toJavaDuration()));
                            });
                })
                .doOnSuccess(__ -> log.debug("Successfully aggregated event '{}' with pending key '{}'",
                        event.getId(), pendingKey))
                .doOnError(error -> log.error("Failed to aggregate event '{}' with pending key '{}': {}",
                        event.getId(), pendingKey, error.getMessage(), error))
                .then();
    }

    /**
     * Retrieves all pending event keys that are ready to be published.
     * An event is ready if: (now - first_seen_timestamp) >= debouncing_window
     *
     * @return Flux of pending keys ready for processing
     */
    public Flux<String> getPendingEventsToPublish() {
        Instant now = Instant.now();
        long debouncingWindowMillis = webhookConfig.getDebouncing().getWindowSize().toMilliseconds();
        
        String pattern = PENDING_KEY_PREFIX + "*";
        
        log.debug("Checking for pending events ready to publish (debouncing window: {}ms)", debouncingWindowMillis);
        
        return redissonClient.getKeys().getKeysByPattern(pattern)
                .flatMap(pendingKey -> {
                    RMapReactive<String, String> pendingEvents = redissonClient.getMap(pendingKey);
                    
                    return pendingEvents.get(EVENT_TIMESTAMP_KEY)
                            .map(Long::parseLong)
                            .filter(firstSeenMillis -> {
                                long elapsedMillis = now.toEpochMilli() - firstSeenMillis;
                                boolean ready = elapsedMillis >= debouncingWindowMillis;
                                
                                if (ready) {
                                    log.debug("Pending key '{}' is ready to publish (elapsed: {}ms >= window: {}ms)",
                                            pendingKey, elapsedMillis, debouncingWindowMillis);
                                } else {
                                    log.trace("Pending key '{}' not ready yet (elapsed: {}ms < window: {}ms)",
                                            pendingKey, elapsedMillis, debouncingWindowMillis);
                                }
                                
                                return ready;
                            })
                            .map(__ -> pendingKey)
                            .switchIfEmpty(Mono.empty());
                })
                .doOnError(error -> log.error("Failed to retrieve pending events to publish: {}",
                        error.getMessage(), error));
    }

    /**
     * Retrieves the aggregated events data from a pending key.
     *
     * @param pendingKey the pending key
     * @return Mono containing the pending events map
     */
    public Mono<RMapReactive<String, String>> getPendingEvents(@NonNull String pendingKey) {
        RMapReactive<String, String> pendingEvents = redissonClient.getMap(pendingKey);

        return Mono.just(pendingEvents)
                .doOnNext(__ -> log.debug("Retrieved pending events: '{}'", pendingKey))
                .doOnError(error -> log.error("Failed to retrieve pending events '{}': {}",
                        pendingKey, error.getMessage(), error));
    }

    /**
     * Deletes a processed pending key from Redis.
     *
     * @param pendingKey the pending key to delete
     * @return Mono that completes when the pending key is deleted
     */
    public Mono<Void> deletePendingEvents(@NonNull String pendingKey) {
        return redissonClient.getMap(pendingKey)
                .delete()
                .doOnSuccess(deleted -> {
                    if (deleted) {
                        log.info("Successfully deleted processed pending events: '{}'", pendingKey);
                    } else {
                        log.warn("Pending key '{}' was already deleted or did not exist", pendingKey);
                    }
                })
                .doOnError(error -> log.error("Failed to delete pending key '{}': {}",
                        pendingKey, error.getMessage(), error))
                .then();
    }

    /**
     * Generates a pending key for an event.
     * Format: "webhook_pending:{alertId}:{eventType}"
     *
     * @param event the webhook event
     * @return the pending key
     */
    private String generatePendingKey(@NonNull WebhookEvent<?> event) {
        return PENDING_KEY_PREFIX + event.getAlertId() + ":" + event.getEventType().name();
    }

    /**
     * Parses event IDs from the JSON-serialized set stored in Redis.
     *
     * @param json the JSON string containing event IDs
     * @return Set of event IDs
     */
    public Set<String> parseEventIds(@NonNull String json) {
        return JsonUtils.readCollectionValue(json, Set.class, String.class);
    }

    /**
     * Parses the alert ID and event type from a pending key.
     *
     * @param pendingKey the pending key in format "webhook_pending:{alertId}:{eventType}"
     * @return array with [alertId, eventType]
     */
    public String[] parsePendingKey(@NonNull String pendingKey) {
        String withoutPrefix = pendingKey.substring(PENDING_KEY_PREFIX.length());
        return withoutPrefix.split(":", 2);
    }
}
