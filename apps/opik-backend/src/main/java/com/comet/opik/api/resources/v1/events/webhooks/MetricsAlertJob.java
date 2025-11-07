package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.domain.AlertService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectMetricsDAO;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.comet.opik.api.AlertTriggerConfig.PROJECT_IDS_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.THRESHOLD_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.WINDOW_CONFIG_KEY;

/**
 * Scheduled job for processing metrics-based alerts.
 *
 * This job runs with configurable delay after each execution completes.
 * Uses fixed-delay scheduling: waits a configured duration AFTER the previous execution completes
 * before starting the next execution, preventing overlapping executions.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MetricsAlertJob implements Managed {

    private static final LockService.Lock METRICS_ALERT_LOCK_KEY = new LockService.Lock("metrics_alert_job:scan_lock");
    private static final EnumSet<AlertEventType> SUPPORTED_EVENT_TYPES = EnumSet.of(
            AlertEventType.TRACE_COST,
            AlertEventType.TRACE_LATENCY);

    private final @NonNull @Config WebhookConfig webhookConfig;
    private final @NonNull LockService lockService;
    private final @NonNull AlertService alertService;
    private final @NonNull ProjectMetricsDAO projectMetricsDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull AlertWebhookSender alertWebhookSender;

    private ScheduledExecutorService scheduler;

    @Override
    public void start() {
        long initialDelaySeconds = webhookConfig.getMetrics().getInitialDelay().toSeconds();
        long fixedDelaySeconds = webhookConfig.getMetrics().getFixedDelay().toSeconds();

        log.info("Starting MetricsAlertJob with initial delay of '{}' seconds and fixed delay of '{}' seconds",
                initialDelaySeconds, fixedDelaySeconds);

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "metrics-alert-job-scheduler");
            thread.setDaemon(true);
            return thread;
        });

        scheduler.scheduleWithFixedDelay(
                this::doJob,
                initialDelaySeconds,
                fixedDelaySeconds,
                TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        log.info("Stopping MetricsAlertJob scheduler");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("MetricsAlertJob scheduler did not terminate in time, forcing shutdown");
                    scheduler.shutdownNow();
                }
                log.info("MetricsAlertJob scheduler stopped successfully");
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for MetricsAlertJob scheduler to stop", e);
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void doJob() {
        log.debug("Starting metrics alert job");

        // Use distributed lock to prevent overlapping runs between different instances
        lockService.bestEffortLock(
                METRICS_ALERT_LOCK_KEY,
                Mono.fromCallable(() -> alertService.findAllByWorkspaceAndEventTypes(null,
                        Set.of(AlertEventType.TRACE_COST, AlertEventType.TRACE_LATENCY)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(Flux::fromIterable)
                        .flatMap(this::processAlert)
                        .onErrorContinue((throwable, alert) -> log.error("Failed to process metrics alert '{}': {}",
                                alert, throwable.getMessage(), throwable))
                        .doOnComplete(() -> log.debug("MetricsAlertJob finished processing all alerts"))
                        .then(),
                Mono.defer(() -> {
                    log.info("Could not acquire lock for metrics alert job, another job instance is running");
                    return Mono.empty();
                }),
                webhookConfig.getMetrics().getMetricsAlertJobTimeout().toJavaDuration(),
                webhookConfig.getMetrics().getMetricsAlertJobLockWaitTimeout().toJavaDuration()).subscribe(
                        __ -> log.debug("Metrics alert job execution completed"),
                        error -> log.error("Metrics alert job interrupted while acquiring lock", error));
    }

    private Mono<Void> processAlert(Alert alert) {
        // Create a unique lock key for this alert to prevent duplicate firing across instances
        LockService.Lock alertLock = new LockService.Lock("metrics_alert:fired:" + alert.id());

        // Try to acquire the lock - if successful, this instance will process the alert
        // If lock already exists, another instance recently fired this alert, so skip it
        return lockService.lockUsingToken(alertLock, webhookConfig.getMetrics().getFixedDelay().toJavaDuration())
                .flatMap(lockAcquired -> {
                    if (Boolean.FALSE.equals(lockAcquired)) {
                        // Lock already exists - alert was recently fired by another instance
                        log.debug(
                                "Skipping alert '{}' (id: '{}') - already fired by another instance within the last '{}' seconds",
                                alert.name(), alert.id(), webhookConfig.getMetrics().getFixedDelay().toSeconds());
                        return Mono.<Void>empty();
                    }

                    // Lock acquired - this instance will process the alert
                    log.debug("Evaluating metrics alert '{}' (id: '{}')", alert.name(), alert.id());

                    // Process each trigger in the alert
                    return Flux.fromIterable(alert.triggers())
                            .filter(trigger -> SUPPORTED_EVENT_TYPES.contains(trigger.eventType()))
                            .flatMap(trigger -> evaluateTrigger(alert, trigger))
                            .then();
                })
                .onErrorResume(error -> {
                    log.error("Failed to process alert '{}' (id: '{}'): {}",
                            alert.name(), alert.id(), error.getMessage(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> evaluateTrigger(Alert alert, AlertTrigger trigger) {
        // Extract configuration from trigger
        TriggerConfig config = extractTriggerConfig(trigger);

        // Calculate time window for query
        Instant endTime = Instant.now();
        Instant startTime = endTime.minusSeconds(config.windowSeconds());

        // Query metrics based on trigger type - this needs to happen with context already set
        Mono<BigDecimal> metricValueMono = switch (trigger.eventType()) {
            case TRACE_COST -> projectMetricsDAO.getTotalCost(
                    alert.workspaceId(),
                    config.projectIds(),
                    startTime,
                    endTime);
            case TRACE_LATENCY -> projectMetricsDAO.getAverageDuration(
                    alert.workspaceId(),
                    config.projectIds(),
                    startTime,
                    endTime);
            default -> Mono.just(BigDecimal.ZERO);
        };

        // For latency, threshold is in seconds but metric value is in milliseconds
        // Convert threshold to milliseconds for comparison
        BigDecimal thresholdForComparison = trigger.eventType() == AlertEventType.TRACE_LATENCY
                ? config.threshold().multiply(BigDecimal.valueOf(1000))
                : config.threshold();

        return metricValueMono
                .doOnNext(value -> log.info("Metric value retrieved: '{}'", value))
                .doOnError(error -> log.error("Error retrieving metric value: '{}'", error.getMessage(), error))
                .flatMap(metricValue -> {
                    // Compare with threshold
                    if (metricValue.compareTo(thresholdForComparison) > 0) {
                        log.info("Alert '{}' (id: '{}') triggered: {} = '{}', threshold = '{}'",
                                alert.name(), alert.id(), trigger.eventType(), metricValue, thresholdForComparison);

                        var metricValueFinal = trigger.eventType() == AlertEventType.TRACE_LATENCY
                                ? metricValue.divide(BigDecimal.valueOf(1000)) // Convert back to seconds for payload
                                : metricValue;

                        // Create payload with metric details
                        String eventId = idGenerator.generateId().toString();
                        String payloadJson = JsonUtils.writeValueAsString(Map.of(
                                "event_type", trigger.eventType().name(),
                                "metric_value", metricValueFinal.toString(),
                                "threshold", config.threshold().toString(),
                                "window_seconds", config.windowSeconds(),
                                "project_ids",
                                config.projectIds() != null
                                        ? config.projectIds().stream().map(UUID::toString)
                                                .collect(Collectors.joining(","))
                                        : ""));

                        // Send webhook directly instead of adding to bucket
                        return alertWebhookSender.createAndSendWebhook(
                                alert,
                                alert.workspaceId(),
                                "",
                                trigger.eventType(),
                                List.of(eventId),
                                List.of(payloadJson),
                                List.of("system")); // System user for automated alerts
                    }

                    log.debug("Alert '{}' (id: '{}') not triggered: {} = '{}', threshold = '{}'",
                            alert.name(), alert.id(), trigger.eventType(), metricValue, thresholdForComparison);
                    return Mono.<Void>empty();
                })
                .contextWrite(context -> AsyncUtils.setRequestContext(context, "system", alert.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private TriggerConfig extractTriggerConfig(AlertTrigger trigger) {
        if (CollectionUtils.isEmpty(trigger.triggerConfigs())) {
            throw new IllegalArgumentException("Trigger must have configuration for metrics alerts");
        }

        // Extract project IDs from SCOPE_PROJECT config type
        var projectIdsString = trigger.triggerConfigs().stream()
                .filter(c -> c.type() == AlertTriggerConfigType.SCOPE_PROJECT)
                .findFirst()
                .map(AlertTriggerConfig::configValue)
                .map(v -> v.get(PROJECT_IDS_CONFIG_KEY))
                .orElse(null);

        List<UUID> projectIds = null;
        if (StringUtils.isNotBlank(projectIdsString)) {
            projectIds = JsonUtils.readCollectionValue(projectIdsString, List.class, UUID.class);
        }

        // Determine which threshold config type to use based on event type
        AlertTriggerConfigType thresholdConfigType = switch (trigger.eventType()) {
            case TRACE_COST -> AlertTriggerConfigType.THRESHOLD_COST;
            case TRACE_LATENCY -> AlertTriggerConfigType.THRESHOLD_LATENCY;
            default -> throw new IllegalArgumentException(
                    "Unsupported event type for metrics alerts: '%s'".formatted(trigger.eventType()));
        };

        // Extract threshold from the appropriate config type
        var thresholdString = trigger.triggerConfigs().stream()
                .filter(c -> c.type() == thresholdConfigType)
                .findFirst()
                .map(AlertTriggerConfig::configValue)
                .map(v -> v.get(THRESHOLD_CONFIG_KEY))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Threshold is required for metrics alerts of type '%s'".formatted(trigger.eventType())));

        BigDecimal threshold = new BigDecimal(thresholdString);

        // Extract window from the same threshold config type
        var windowString = trigger.triggerConfigs().stream()
                .filter(c -> c.type() == thresholdConfigType)
                .findFirst()
                .map(AlertTriggerConfig::configValue)
                .map(v -> v.get(WINDOW_CONFIG_KEY))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Time window is required for metrics alerts of type '%s'".formatted(trigger.eventType())));

        long windowSeconds = Long.parseLong(windowString);

        return new TriggerConfig(projectIds, threshold, windowSeconds);
    }

    private record TriggerConfig(List<UUID> projectIds, BigDecimal threshold, long windowSeconds) {
    }
}
