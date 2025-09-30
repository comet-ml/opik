package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.WebhookSubscriber;
import com.comet.opik.domain.AlertService;
import io.dropwizard.jobs.Job;
import io.dropwizard.jobs.annotations.Every;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Scheduled job responsible for managing alert buckets and triggering webhooks.
 * 
 * This job:
 * 1. Checks alert buckets in Redis every 5 seconds
 * 2. For buckets that have passed the debouncing window:
 *    - Retrieves alert configuration
 *    - Creates consolidated webhook event
 *    - Triggers webhook via WebhookSubscriber
 *    - Deletes processed bucket
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@Every("5s")
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AlertJob extends Job {

    private final @NonNull AlertBucketService bucketService;
    private final @NonNull AlertService alertService;
    private final @NonNull WebhookSubscriber webhookSubscriber;

    @Override
    public void doJob(JobExecutionContext context) {
        log.debug("Starting alert job - checking for buckets to process");

        bucketService.getBucketsReadyToProcess()
                .flatMap(this::processBucket)
                .onErrorContinue((throwable, bucketKey) -> {
                    log.error("Failed to process bucket '{}': {}",
                            bucketKey, throwable.getMessage(), throwable);
                })
                .blockLast();

        log.debug("Alert job completed");
    }

    /**
     * Processes a single bucket by:
     * 1. Parsing alert ID and event type
     * 2. Retrieving alert configuration
     * 3. Getting aggregated event IDs
     * 4. Creating and sending consolidated webhook
     * 5. Deleting the bucket
     *
     * @param bucketKey the bucket key to process
     * @return Mono that completes when the bucket is processed
     */
    private Mono<Void> processBucket(@NonNull String bucketKey) {
        log.info("Processing bucket: '{}'", bucketKey);

        return Mono.fromCallable(() -> bucketService.parseBucketKey(bucketKey))
                .flatMap(parts -> {
                    UUID alertId = UUID.fromString(parts[0]);
                    AlertEventType eventType = AlertEventType.fromString(parts[1]);

                    log.debug("Parsed bucket key: alertId='{}', eventType='{}'", alertId, eventType);

                    return bucketService.getBucketEventIds(bucketKey)
                            .flatMap(eventIds -> processAlertBucket(alertId, eventType, eventIds))
                            .then(bucketService.deleteBucket(bucketKey));
                })
                .doOnSuccess(__ -> log.info("Successfully processed and deleted bucket: '{}'", bucketKey))
                .doOnError(error -> log.error("Failed to process bucket '{}': {}",
                        bucketKey, error.getMessage(), error));
    }

    /**
     * Processes an alert bucket by retrieving the alert configuration
     * and sending a consolidated webhook notification.
     *
     * @param alertId the alert ID
     * @param eventType the event type
     * @param eventIds the set of aggregated event IDs
     * @return Mono that completes when the webhook is sent
     */
    private Mono<Void> processAlertBucket(
            @NonNull UUID alertId,
            @NonNull AlertEventType eventType,
            @NonNull Set<String> eventIds) {

        log.info("Processing alert bucket: alertId='{}', eventType='{}', eventCount='{}'",
                alertId, eventType, eventIds.size());

        return Mono.fromCallable(() -> alertService.getById(alertId))
                .flatMap(alert -> createAndSendWebhook(alert, eventType, eventIds))
                .doOnSuccess(__ -> log.info("Successfully sent webhook for alert '{}' with '{}' events",
                        alertId, eventIds.size()))
                .doOnError(error -> log.error("Failed to send webhook for alert '{}': {}",
                        alertId, error.getMessage(), error));
    }

    /**
     * Creates a consolidated webhook event and sends it via WebhookSubscriber.
     *
     * @param alert the alert configuration
     * @param eventType the event type
     * @param eventIds the set of aggregated event IDs
     * @return Mono that completes when the webhook is sent
     */
    private Mono<Void> createAndSendWebhook(
            @NonNull Alert alert,
            @NonNull AlertEventType eventType,
            @NonNull Set<String> eventIds) {

        if (!alert.enabled()) {
            log.warn("Alert '{}' is disabled, skipping webhook", alert.id());
            return Mono.empty();
        }

        if (alert.webhook() == null || alert.webhook().url() == null) {
            log.error("Alert '{}' has no webhook configuration, skipping", alert.id());
            return Mono.empty();
        }

        log.debug("Creating consolidated webhook event for alert '{}' with '{}' events",
                alert.id(), eventIds.size());

        // Create consolidated webhook event
        WebhookEvent<Map<String, Object>> webhookEvent = WebhookEvent.<Map<String, Object>>builder()
                .id("alert-" + alert.id() + "-" + UUID.randomUUID())
                .eventType(com.comet.opik.api.events.webhooks.WebhookEventTypes.ALERT_FIRED)
                .alertId(alert.id())
                .workspaceId("") // TODO: Get workspace ID from context or alert
                .userName("system")
                .url(alert.webhook().url())
                .payload(Map.of(
                        "alertId", alert.id().toString(),
                        "alertName", alert.name(),
                        "eventType", eventType.getValue(),
                        "eventIds", eventIds,
                        "eventCount", eventIds.size(),
                        "aggregationType", "consolidated",
                        "message", String.format("Alert '%s': %d %s events aggregated",
                                alert.name(), eventIds.size(), eventType.getValue())
                ))
                .createdAt(Instant.now())
                .maxRetries(3)
                .headers(alert.webhook().headers() != null ? alert.webhook().headers() : Map.of())
                .build();

        log.info("Sending webhook for alert '{}' ({}): url='{}', eventCount='{}'",
                alert.name(), alert.id(), alert.webhook().url(), eventIds.size());

        // Send via WebhookSubscriber
        return webhookSubscriber.sendWebhook(webhookEvent)
                .doOnSuccess(__ -> log.info("Successfully sent webhook for alert '{}' ({})",
                        alert.name(), alert.id()))
                .doOnError(error -> log.error("Failed to send webhook for alert '{}' ({}): {}",
                        alert.name(), alert.id(), error.getMessage(), error));
    }
}
