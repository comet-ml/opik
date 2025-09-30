package com.comet.opik.api.resources.v1.events.webhooks;

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

/**
 * Scheduled job that processes pending webhook events based on debouncing window.
 * Runs every 5 seconds to check for events that are ready to be published.
 * For each pending key (alert+event type combination), it sends one consolidated notification.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@Every("5s") // Run every 5 seconds
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class WebhookBucketProcessorJob extends Job {

    private final @NonNull WebhookEventAggregationService aggregationService;
    private final @NonNull WebhookBucketProcessor bucketProcessor;

    @Override
    public void doJob(JobExecutionContext context) {
        log.debug("Starting webhook debounce check");

        aggregationService.getPendingEventsToPublish()
                .flatMap(pendingKey -> processPendingEvents(pendingKey)
                        .doOnError(error -> log.error("Failed to process pending events '{}': {}",
                                pendingKey, error.getMessage(), error))
                        .onErrorResume(error -> Mono.empty())) // Continue processing other events on error
                .doOnComplete(() -> log.debug("Completed webhook debounce check"))
                .doOnError(error -> log.error("Webhook debounce check failed: {}",
                        error.getMessage(), error))
                .subscribe(); // Fire and forget - don't block the scheduler
    }

    /**
     * Processes a single pending key by sending a consolidated notification and then deleting it.
     *
     * @param pendingKey the pending key to process
     * @return Mono that completes when the pending events are fully processed
     */
    private Mono<Void> processPendingEvents(String pendingKey) {
        log.info("Processing pending events: '{}'", pendingKey);

        return aggregationService.getPendingEvents(pendingKey)
                .flatMap(pendingEvents -> bucketProcessor.processPendingEvents(pendingEvents, pendingKey)
                        .then(aggregationService.deletePendingEvents(pendingKey)))
                .doOnSuccess(__ -> log.info("Successfully processed and deleted pending events: '{}'", pendingKey))
                .doOnError(error -> log.error("Failed to process pending events '{}': {}",
                        pendingKey, error.getMessage(), error));
    }
}
