package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.Project;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.domain.AlertService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectMetricsDAO;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.NumberUtils;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.AlertTriggerConfig.NAME_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.OPERATOR_CONFIG_KEY;
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
public class MetricsAlertJob extends Job implements InterruptableJob {

    public static final EnumSet<AlertEventType> SUPPORTED_EVENT_TYPES = EnumSet.of(
            AlertEventType.TRACE_COST,
            AlertEventType.TRACE_LATENCY,
            AlertEventType.TRACE_ERRORS,
            AlertEventType.TRACE_FEEDBACK_SCORE,
            AlertEventType.TRACE_THREAD_FEEDBACK_SCORE);
    private static final BigDecimal MILLISECONDS_PER_SECOND = BigDecimal.valueOf(1000);
    private volatile boolean interrupted = false;

    private final @NonNull WebhookConfig webhookConfig;
    private final @NonNull LockService lockService;
    private final @NonNull AlertService alertService;
    private final @NonNull ProjectMetricsDAO projectMetricsDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull AlertWebhookSender alertWebhookSender;

    // OpenTelemetry metrics for monitoring alerts
    private final LongCounter alertsFired;
    private final LongCounter alertsSkipped;
    private final LongCounter alertsErrors;

    @Inject
    public MetricsAlertJob(@NonNull @Config WebhookConfig webhookConfig,
            @NonNull LockService lockService,
            @NonNull AlertService alertService,
            @NonNull ProjectMetricsDAO projectMetricsDAO,
            @NonNull ProjectService projectService,
            @NonNull IdGenerator idGenerator,
            @NonNull AlertWebhookSender alertWebhookSender) {
        this.webhookConfig = webhookConfig;
        this.lockService = lockService;
        this.alertService = alertService;
        this.projectMetricsDAO = projectMetricsDAO;
        this.projectService = projectService;
        this.idGenerator = idGenerator;
        this.alertWebhookSender = alertWebhookSender;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.alerts");

        this.alertsFired = meter
                .counterBuilder("opik.alerts.fired")
                .setDescription("Number of alerts sent to configured webhooks")
                .build();

        this.alertsSkipped = meter
                .counterBuilder("opik.alerts.skipped")
                .setDescription("Number of alerts evaluated but not triggered")
                .build();

        this.alertsErrors = meter
                .counterBuilder("opik.alerts.errors")
                .setDescription("Number of errors during alerts processing")
                .build();
    }

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
                    alertsErrors.add(1);
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
        // Extract all configurations from trigger
        List<TriggerConfig> configs = extractTriggerConfig(trigger);
        if (configs.isEmpty()) {
            log.warn(
                    "Skipping alert: no trigger configs found for alert '{}' (id: '{}'), trigger: '{}', trigger id: '{}'",
                    alert.name(), alert.id(), trigger.eventType(), trigger.id());
            alertsSkipped.add(1);
            return Mono.empty();
        }

        log.info("Evaluating '{}' config(s) for alert '{}' (id: '{}'), event type: '{}'",
                configs.size(), alert.name(), alert.id(), trigger.eventType());

        // Evaluate all configs and fire alerts for each one that triggers
        return Flux.fromIterable(configs)
                .flatMap(config -> evaluateSingleConfig(alert, trigger, config))
                .then();
    }

    private Mono<Void> evaluateSingleConfig(Alert alert, AlertTrigger trigger, TriggerConfig config) {
        log.info(
                "Evaluating config for alert '{}' (id: '{}'), event type: '{}', name: '{}', operator: '{}', threshold: '{}', window: '{}'s",
                alert.name(), alert.id(), trigger.eventType(), config.name(), config.operator(), config.threshold(),
                config.windowSeconds());

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
            case TRACE_ERRORS -> projectMetricsDAO.getTotalTraceErrors(
                    config.projectIds(),
                    startTime,
                    endTime);
            case TRACE_FEEDBACK_SCORE -> projectMetricsDAO.getAverageFeedbackScore(
                    config.projectIds(),
                    startTime,
                    endTime,
                    EntityType.TRACE,
                    config.name());
            case TRACE_THREAD_FEEDBACK_SCORE -> projectMetricsDAO.getAverageFeedbackScore(
                    config.projectIds(),
                    startTime,
                    endTime,
                    EntityType.THREAD,
                    config.name());
            default -> Mono.just(BigDecimal.ZERO);
        };

        // For latency, threshold is in seconds but metric value is in milliseconds
        // Convert threshold to milliseconds for comparison
        BigDecimal thresholdForComparison = trigger.eventType() == AlertEventType.TRACE_LATENCY
                ? config.threshold().multiply(MILLISECONDS_PER_SECOND)
                : config.threshold();

        return metricValueMono
                .doOnNext(
                        value -> log.info(
                                "Metric value retrieved: '{}', for workspace '{}', alert '{}', trigger: '{}', name: '{}'",
                                value, alert.workspaceId(), alert.name(), trigger.eventType(), config.name()))
                .doOnError(error -> log.error(
                        "Error retrieving metric value: '{}', for workspace '{}', alert '{}', trigger: '{}', name: '{}'",
                        error.getMessage(), alert.workspaceId(), alert.name(), trigger.eventType(), config.name(),
                        error))
                .switchIfEmpty(Mono.defer(() -> {
                    alertsSkipped.add(1);
                    log.info("No metric data found for alert '{}' (id: '{}'), trigger: '{}', name: '{}' in time window",
                            alert.name(), alert.id(), trigger.eventType(), config.name());
                    return Mono.empty();
                }))
                .flatMap(metricValue -> {
                    // Compare with threshold
                    if (compareMetric(metricValue, thresholdForComparison, config.operator())) {
                        log.info("Alert '{}' (id: '{}') triggered: {} = '{}', threshold = '{}', name: '{}'",
                                alert.name(), alert.id(), trigger.eventType(), metricValue, thresholdForComparison,
                                config.name());

                        alertsFired.add(1);
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
                                    .metricValue(NumberUtils.formatDecimal(metricValueFinal))
                                    .threshold(NumberUtils.formatDecimal(config.threshold()))
                                    .windowSeconds(config.windowSeconds())
                                    .feedbackScoreName(config.name())
                                    .projectIds(config.projectIds() != null
                                            ? config.projectIds().stream().map(UUID::toString)
                                                    .collect(Collectors.joining(","))
                                            : "")
                                    .projectNames(config.projectIds() != null
                                            ? projectService
                                                    .findByIds(alert.workspaceId(), Set.copyOf(config.projectIds()))
                                                    .stream()
                                                    .map(Project::name)
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

                    alertsSkipped.add(1);
                    log.debug("Alert '{}' (id: '{}') not triggered: {} = '{}', threshold = '{}', name: '{}'",
                            alert.name(), alert.id(), trigger.eventType(), metricValue, thresholdForComparison,
                            config.name());
                    return Mono.<Void>empty();
                })
                .contextWrite(context -> AsyncUtils.setRequestContext(context, "system", alert.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private boolean compareMetric(BigDecimal metricValue, BigDecimal threshold, Operator operator) {
        return switch (operator) {
            case GREATER_THAN -> metricValue.compareTo(threshold) > 0;
            case LESS_THAN -> metricValue.compareTo(threshold) < 0;
        };
    }

    private List<TriggerConfig> extractTriggerConfig(AlertTrigger trigger) {
        if (CollectionUtils.isEmpty(trigger.triggerConfigs())) {
            log.warn("Trigger has no configuration for metrics alert: event type: '{}', trigger id: '{}'",
                    trigger.eventType(), trigger.id());
            return List.of();
        }

        // Extract project IDs from SCOPE_PROJECT config type (same for all configs)
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
            case TRACE_ERRORS -> AlertTriggerConfigType.THRESHOLD_ERRORS;
            case TRACE_FEEDBACK_SCORE, TRACE_THREAD_FEEDBACK_SCORE -> AlertTriggerConfigType.THRESHOLD_FEEDBACK_SCORE;
            default -> throw new IllegalArgumentException(
                    "Unsupported event type for metrics alerts: '%s'".formatted(trigger.eventType()));
        };

        // Extract ALL threshold configs of the appropriate type (not just the first one)
        final List<UUID> finalProjectIds = projectIds;
        return trigger.triggerConfigs().stream()
                .filter(c -> c.type() == thresholdConfigType)
                .map(config -> {
                    // Extract threshold
                    var thresholdString = config.configValue().get(THRESHOLD_CONFIG_KEY);
                    if (thresholdString == null) {
                        throw new IllegalArgumentException(
                                "Missing config value for key '%s' in trigger of type '%s'"
                                        .formatted(THRESHOLD_CONFIG_KEY, thresholdConfigType));
                    }
                    BigDecimal threshold = new BigDecimal(thresholdString);

                    // Extract window
                    var windowString = config.configValue().get(WINDOW_CONFIG_KEY);
                    if (windowString == null) {
                        throw new IllegalArgumentException(
                                "Missing config value for key '%s' in trigger of type '%s'"
                                        .formatted(WINDOW_CONFIG_KEY, thresholdConfigType));
                    }
                    long windowSeconds = Long.parseLong(windowString);

                    // Extract name and operator for feedback score alerts
                    String name = null;
                    Operator operator = Operator.GREATER_THAN;
                    if (trigger.eventType() == AlertEventType.TRACE_FEEDBACK_SCORE
                            || trigger.eventType() == AlertEventType.TRACE_THREAD_FEEDBACK_SCORE) {
                        name = config.configValue().get(NAME_CONFIG_KEY);
                        if (name == null) {
                            throw new IllegalArgumentException(
                                    "Missing config value for key '%s' in trigger of type '%s'"
                                            .formatted(NAME_CONFIG_KEY, thresholdConfigType));
                        }
                        var operatorString = config.configValue().get(OPERATOR_CONFIG_KEY);
                        if (operatorString == null) {
                            throw new IllegalArgumentException(
                                    "Missing config value for key '%s' in trigger of type '%s'"
                                            .formatted(OPERATOR_CONFIG_KEY, thresholdConfigType));
                        }
                        operator = Operator.fromString(operatorString);
                    }

                    return new TriggerConfig(finalProjectIds, threshold, windowSeconds, name, operator);
                })
                .collect(Collectors.toList());
    }

    private record TriggerConfig(List<UUID> projectIds, BigDecimal threshold, long windowSeconds, String name,
            Operator operator) {
    }

    @RequiredArgsConstructor
    @Getter
    public enum Operator {
        GREATER_THAN(">"),
        LESS_THAN("<"),
        ;

        @JsonValue
        private final String value;

        @JsonCreator
        public static Operator fromString(String value) {
            return Arrays.stream(values())
                    .filter(enumValue -> enumValue.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown Operator '%s'".formatted(value)));
        }
    }
}
