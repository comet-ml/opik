package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.domain.AlertService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectMetricsDAO;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.UnableToInterruptJobException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuples;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.AlertTriggerConfig.PROJECT_IDS_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.THRESHOLD_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.WINDOW_CONFIG_KEY;

/**
 * Scheduled job for processing metrics-based alerts.
 *
 * This job evaluates cost and latency thresholds for configured alerts at a configurable interval.
 * Uses Quartz scheduling with @DisallowConcurrentExecution to prevent overlapping executions
 * across multiple backend instances and within a single instance.
 *
 * The job interval is configured via webhookConfig.metrics.fixedDelay and scheduled
 * programmatically in OpikGuiceyLifecycleEventListener.
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MetricsAlertJob extends Job implements InterruptableJob {

    private static final LockService.Lock METRICS_ALERT_LOCK_KEY = new LockService.Lock("metrics_alert_job:scan_lock");
    private static final EnumSet<AlertEventType> SUPPORTED_EVENT_TYPES = EnumSet.of(
            AlertEventType.TRACE_COST,
            AlertEventType.TRACE_LATENCY);
    private static final BigDecimal MILLISECONDS_PER_SECOND = BigDecimal.valueOf(1000);
    private volatile boolean interrupted = false;

    private final @NonNull @Config WebhookConfig webhookConfig;
    private final @NonNull LockService lockService;
    private final @NonNull AlertService alertService;
    private final @NonNull ProjectMetricsDAO projectMetricsDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull AlertWebhookSender alertWebhookSender;

    @Override
    public void doJob(JobExecutionContext context) {
        if (isInterrupted()) {
            log.info("Metrics alert job interrupted before start");
            return;
        }
        log.debug("Starting metrics alert job");

        Mono.fromCallable(() -> alertService.findAllByWorkspaceAndEventTypes(null,
                SUPPORTED_EVENT_TYPES))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .takeWhile(__ -> !isInterrupted())
                .flatMap(this::processAlert)
                .onErrorContinue((throwable, alert) -> log.error("Failed to process metrics alert '{}': {}",
                        alert, throwable.getMessage(), throwable))
                .doOnComplete(() -> log.debug("MetricsAlertJob finished processing all alerts"))
                .subscribe();
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        log.info("Interrupt requested for MetricsAlertJob");
        interrupted = true;
    }

    private boolean isInterrupted() {
        return interrupted || Thread.currentThread().isInterrupted();
    }

    private Mono<Void> processAlert(Alert alert) {
        if (isInterrupted()) {
            log.info("Skipping alert '{}' due to job interruption", alert.id());
            return Mono.empty();
        }
        // Create a unique lock key for this alert to prevent duplicate firing across instances
        LockService.Lock alertLock = new LockService.Lock("metrics_alert:fired:" + alert.id());

        // Calculate lock duration: job interval - 1 minute to ensure it expires before the next job run
        // This allows alerts to fire on every job run instead of every other run
        Duration jobInterval = webhookConfig.getMetrics().getFixedDelay().toJavaDuration();
        Duration lockDuration = jobInterval.toMinutes() > 1
                ? jobInterval.minusMinutes(1)
                : jobInterval.minusSeconds(jobInterval.getSeconds() / 2);

        // Try to acquire the lock - if successful, this instance will process the alert
        // If lock already exists, another instance recently fired this alert, so skip it
        return lockService.lockUsingToken(alertLock, lockDuration)
                .flatMap(lockAcquired -> {
                    if (Boolean.FALSE.equals(lockAcquired)) {
                        // Lock already exists - alert was recently fired by another instance
                        log.debug(
                                "Skipping alert '{}' (id: '{}') - already fired by another instance recently",
                                alert.name(), alert.id());
                        return Mono.<Void>empty();
                    }

                    // Lock acquired - this instance will process the alert
                    log.debug("Evaluating metrics alert '{}' (id: '{}')", alert.name(), alert.id());

                    // Process each trigger in the alert
                    return Flux.fromIterable(alert.triggers())
                            .filter(trigger -> SUPPORTED_EVENT_TYPES.contains(trigger.eventType()))
                            .flatMap(trigger -> evaluateTrigger(alert, trigger).contextWrite(
                                    ctx -> ctx.put(RequestContext.WORKSPACE_ID, alert.workspaceId())))
                            .then();
                })
                .onErrorResume(error -> {
                    log.error("Failed to process alert '{}' (id: '{}'): {}",
                            alert.name(), alert.id(), error.getMessage(), error);
                    return Mono.empty();
                });
    }

    private Mono<Void> evaluateTrigger(Alert alert, AlertTrigger trigger) {
        if (isInterrupted()) {
            log.info("Skipping trigger evaluation due to job interruption for alert '{}'", alert.id());
            return Mono.empty();
        }
        // Extract configuration from trigger
        TriggerConfig config = extractTriggerConfig(trigger);

        // Calculate time window for query
        Instant endTime = Instant.now();
        Instant startTime = endTime.minusSeconds(config.windowSeconds());

        // Query metrics based on trigger type - this needs to happen with context already set
        Mono<BigDecimal> metricValueMono = switch (trigger.eventType()) {
            case TRACE_COST -> projectMetricsDAO.getTotalCost(
                    config.projectIds(),
                    startTime,
                    endTime);
            case TRACE_LATENCY -> projectMetricsDAO.getAverageDuration(
                    config.projectIds(),
                    startTime,
                    endTime);
            default -> Mono.just(BigDecimal.ZERO);
        };

        // For latency, threshold is in seconds but metric value is in milliseconds
        // Convert threshold to milliseconds for comparison
        BigDecimal thresholdForComparison = trigger.eventType() == AlertEventType.TRACE_LATENCY
                ? config.threshold().multiply(MILLISECONDS_PER_SECOND)
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
                                ? metricValue.divide(MILLISECONDS_PER_SECOND, 9, RoundingMode.HALF_UP) // Convert back to seconds for payload
                                : metricValue;

                        // Wrap blocking JSON serialization in Mono.fromCallable
                        return Mono.fromCallable(() -> {
                            String eventId = idGenerator.generateId().toString();

                            // Create MetricsAlertPayload DTO
                            var metricsPayload = MetricsAlertPayload.builder()
                                    .eventType(trigger.eventType().name())
                                    .metricName(trigger.eventType().getValue())
                                    .metricValue(metricValueFinal)
                                    .threshold(config.threshold())
                                    .windowSeconds(config.windowSeconds())
                                    .projectIds(config.projectIds() != null
                                            ? config.projectIds().stream().map(UUID::toString)
                                                    .collect(Collectors.joining(","))
                                            : "")
                                    .build();

                            String payloadJson = JsonUtils.writeValueAsString(metricsPayload);
                            return Tuples.of(eventId, payloadJson);
                        })
                                .flatMap(payload -> alertWebhookSender.createAndSendWebhook(
                                        alert,
                                        alert.workspaceId(),
                                        "",
                                        trigger.eventType(),
                                        List.of(payload.getT1()),
                                        List.of(payload.getT2()),
                                        List.of("system"))); // System user for automated alerts
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
