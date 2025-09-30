package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.events.webhooks.WebhookEventTypes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMapReactive;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processor for pending webhook events that sends consolidated notifications.
 * For each pending key (alert ID + event type combination),
 * it sends one consolidated notification containing all aggregated event IDs.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class WebhookBucketProcessor {

    private final @NonNull WebhookHttpClient webhookHttpClient;
    private final @NonNull WebhookEventAggregationService aggregationService;

    /**
     * Processes pending events by sending a consolidated notification.
     *
     * @param pendingEvents the Redis map containing the pending event data
     * @param pendingKey the pending key for parsing alert ID and event type
     * @return Mono that completes when the notification is sent
     */
    public Mono<Void> processPendingEvents(
            @NonNull RMapReactive<String, String> pendingEvents,
            @NonNull String pendingKey) {
        
        return pendingEvents.get("eventIds")
                .defaultIfEmpty("[]")
                .flatMap(eventIdsJson -> sendConsolidatedNotification(pendingKey, eventIdsJson))
                .doOnError(error -> log.error("Failed to send consolidated notification for key '{}': {}",
                        pendingKey, error.getMessage(), error));
    }

    /**
     * Sends a consolidated notification for a specific alert+event type combination.
     *
     * @param pendingKey the pending key in format "webhook_pending:{alertId}:{eventType}"
     * @param eventIdsJson the JSON-serialized set of event IDs
     * @return Mono that completes when the notification is sent
     */
    private Mono<Void> sendConsolidatedNotification(@NonNull String pendingKey, @NonNull String eventIdsJson) {
        String[] parts = aggregationService.parsePendingKey(pendingKey);
        UUID alertId = UUID.fromString(parts[0]);
        WebhookEventTypes eventType = WebhookEventTypes.valueOf(parts[1]);
        Set<String> eventIds = aggregationService.parseEventIds(eventIdsJson);

        log.info("Sending consolidated notification for alert '{}', event type '{}', with '{}' events",
                alertId, eventType, eventIds.size());

        // Create a consolidated webhook event
        WebhookEvent<Map<String, Object>> consolidatedEvent = WebhookEvent.<Map<String, Object>>builder()
                .id("consolidated-" + UUID.randomUUID())
                .eventType(eventType)
                .alertId(alertId)
                .workspaceId("") // Will be populated from alert configuration
                .userName("system")
                .url("") // Will be populated from alert configuration
                .payload(Map.of(
                        "eventIds", eventIds,
                        "eventCount", eventIds.size(),
                        "aggregationType", "consolidated",
                        "message", String.format("%d %s events aggregated", eventIds.size(), eventType.name())))
                .createdAt(Instant.now())
                .maxRetries(3)
                .headers(Map.of())
                .build();

        // TODO: Fetch alert configuration to get workspace ID, URL, and custom headers
        // For now, this is a placeholder implementation
        log.debug("Consolidated event created: '{}'", consolidatedEvent.getId());

        // Send the notification via HTTP client
        return webhookHttpClient.sendWebhook(consolidatedEvent)
                .doOnSuccess(__ -> log.info("Successfully sent consolidated notification for '{}' with '{}' events",
                        pendingKey, eventIds.size()))
                .doOnError(error -> log.error("Failed to send consolidated notification for '{}': {}",
                        pendingKey, error.getMessage(), error))
                .then();
    }
}
