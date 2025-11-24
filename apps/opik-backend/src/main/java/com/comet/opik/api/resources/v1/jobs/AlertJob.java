package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.domain.AlertService;
import com.comet.opik.domain.alerts.AlertBucketService;
import com.comet.opik.domain.alerts.AlertWebhookSender;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.lock.LockService;
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
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

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

    private static final Lock SCAN_LOCK_KEY = new Lock("alert_job:scan_lock");

    private final @NonNull AlertBucketService bucketService;
    private final @NonNull AlertService alertService;
    private final @NonNull @Config WebhookConfig webhookConfig;
    private final @NonNull LockService lockService;
    private final @NonNull AlertWebhookSender alertWebhookSender;

    @Override
    public void doJob(JobExecutionContext context) {
        log.debug("Starting alert job - checking for buckets to process");

        // Use distributed lock to prevent overlapping scans in case of slow Redis SCAN operations
        lockService.bestEffortLock(
                SCAN_LOCK_KEY,
                Mono.defer(() -> bucketService.getBucketsReadyToProcess()
                        .flatMap(this::processBucket)
                        .onErrorContinue((throwable, bucketKey) -> log.error("Failed to process bucket '{}': {}",
                                bucketKey, throwable.getMessage(), throwable))
                        .doOnComplete(() -> log.debug("Alert job finished processing all ready buckets"))
                        .then()),
                Mono.defer(() -> {
                    log.info("Could not acquire lock for scanning buckets, another job instance is running");
                    return Mono.empty();
                }),
                webhookConfig.getDebouncing().getAlertJobTimeout().toJavaDuration(),
                webhookConfig.getDebouncing().getAlertJobLockWaitTimeout().toJavaDuration()).subscribe(
                        __ -> log.info("Alert job execution completed"),
                        error -> log.error("Alert job interrupted while acquiring lock", error));
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

                    return bucketService.getBucketData(bucketKey)
                            .flatMap(bucketData -> processAlertBucket(
                                    alertId,
                                    bucketData.workspaceId(),
                                    bucketData.workspaceName(),
                                    eventType,
                                    bucketData.eventIds(),
                                    bucketData.payloads(),
                                    bucketData.userNames()))
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
     * @param workspaceId the workspace ID for the alert
     * @param workspaceName the workspace name for the alert
     * @param eventType the event type
     * @param eventIds the set of aggregated event IDs
     * @param payloads the set of aggregated event payloads
     * @return Mono that completes when the webhook is sent
     */
    private Mono<Void> processAlertBucket(
            @NonNull UUID alertId,
            @NonNull String workspaceId,
            @NonNull String workspaceName,
            @NonNull AlertEventType eventType,
            @NonNull List<String> eventIds,
            @NonNull List<String> payloads,
            @NonNull List<String> userNames) {

        log.info(
                "Processing alert bucket: alertId='{}', workspaceId='{}', workspaceName='{}', eventType='{}', eventCount='{}', payloadCount='{}'",
                alertId, workspaceId, workspaceName, eventType, eventIds.size(), payloads.size());

        return Mono.fromCallable(() -> alertService.getByIdAndWorkspace(alertId, workspaceId))
                .flatMap(alert -> alertWebhookSender.createAndSendWebhook(alert, workspaceId, workspaceName, eventType,
                        eventIds, payloads, userNames))
                .doOnSuccess(
                        __ -> log.info("Successfully sent webhook for alert '{}' with '{}' events and '{}' payloads",
                                alertId, eventIds.size(), payloads.size()))
                .doOnError(error -> log.error("Failed to send webhook for alert '{}': {}",
                        alertId, error.getMessage(), error));
    }

}
