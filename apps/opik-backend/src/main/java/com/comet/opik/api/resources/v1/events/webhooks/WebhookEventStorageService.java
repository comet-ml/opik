package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.WebhookSubscriber;
import com.comet.opik.infrastructure.WebhookConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Service responsible for receiving webhook events and storing them in Redis for aggregation.
 * This is the entry point for webhook events before they are sent via WebhookSubscriber.
 * 
 * If debouncing is enabled:
 *   - Events are stored in Redis via WebhookEventAggregationService
 *   - Background job processes aggregated events and sends them via WebhookSubscriber
 * 
 * If debouncing is disabled:
 *   - Events are sent immediately via WebhookSubscriber
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class WebhookEventStorageService {

    private final @NonNull WebhookEventAggregationService aggregationService;
    private final @NonNull WebhookSubscriber webhookSubscriber;
    private final @NonNull WebhookConfig webhookConfig;

    /**
     * Stores a webhook event for processing.
     * If debouncing is enabled, the event is aggregated in Redis.
     * If debouncing is disabled, the event is sent immediately.
     *
     * @param event the webhook event to store
     * @return Mono that completes when the event is stored or sent
     */
    public Mono<Void> storeEvent(@NonNull WebhookEvent<?> event) {
        log.debug("Storing webhook event: id='{}', type='{}', alertId='{}'",
                event.getId(), event.getEventType(), event.getAlertId());

        if (webhookConfig.getDebouncing().isEnabled()) {
            log.debug("Debouncing enabled, aggregating event '{}' in Redis", event.getId());
            return aggregationService.aggregateEvent(event);
        } else {
            log.debug("Debouncing disabled, sending event '{}' immediately", event.getId());
            return webhookSubscriber.sendWebhook(event);
        }
    }
}
