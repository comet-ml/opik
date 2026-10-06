package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.resources.v1.events.webhooks.slack.AlertPayloadAdapter;
import com.comet.opik.api.resources.v1.events.webhooks.slack.SlackWebhookPayloadMapper;
import com.comet.opik.infrastructure.AlertsEventBridgeConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_NAME_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.errorType;

/**
 * Best-effort publishing of triggered alerts to the deployment's AWS EventBridge bus. Failures are logged and
 * counted, never propagated, so they can't affect webhook delivery.
 */
@Slf4j
@Singleton
public class AlertEventBridgePublisher {

    static final String SOURCE = "comet.opik";
    static final String DETAIL_TYPE = "Opik Alert";
    static final String SCHEMA_VERSION = "1";
    // PutEvents caps an entry at 256 KB, which also counts Source, DetailType and the bus name
    static final int MAX_DETAIL_BYTES = 250_000;
    static final int MAX_TRUNCATED_LIST_SIZE = 1_000;

    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(200);
    private static final Set<String> RETRYABLE_ENTRY_ERROR_CODES = Set.of("ThrottlingException", "InternalFailure");
    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey("result");

    private final Optional<EventBridgeClient> client;
    private final String eventBus;
    private final LongCounter publishCounter;

    @Inject
    public AlertEventBridgePublisher(@NonNull Optional<EventBridgeClient> client,
            @NonNull @Config("alertsEventBridge") AlertsEventBridgeConfig config) {
        this.client = client;
        this.eventBus = config.getEventBus();
        this.publishCounter = GlobalOpenTelemetry.get().getMeter("opik.alerts")
                .counterBuilder("opik_alerts_eventbridge_publish_total")
                .setDescription("Number of triggered alerts published to AWS EventBridge, by result")
                .build();
    }

    public boolean isEnabled() {
        return client.isPresent();
    }

    /**
     * @param workspaceName null when it couldn't be resolved, in which case the envelope omits it
     * @param payload the product payload built for the webhook, with its metadata still serialized
     * @return a Mono that completes once the alert is published or the publish has failed; it never errors
     */
    public Mono<Void> publish(@NonNull Alert alert,
            @NonNull String workspaceId,
            @Nullable String workspaceName,
            @NonNull AlertEventType eventType,
            @NonNull Map<String, Object> payload) {
        if (client.isEmpty()) {
            return Mono.empty();
        }

        Instant triggeredAt = Instant.now();

        return Mono.fromCallable(() -> buildEntry(alert, workspaceId, workspaceName, eventType, payload,
                triggeredAt))
                .flatMap(entry -> putEvent(entry, alert))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(__ -> {
                    publishCounter.add(1, metricAttributes(workspaceId, workspaceName, "success"));
                    log.info("Published alert to EventBridge, alertName='{}', alertId='{}', bus='{}'",
                            alert.name(), alert.id(), eventBus);
                })
                .onErrorResume(error -> {
                    publishCounter.add(1, metricAttributes(workspaceId, workspaceName, "error").toBuilder()
                            .put(ERROR_TYPE_KEY, errorType(error))
                            .build());
                    log.error("Failed to publish alert to EventBridge, alertName='{}', alertId='{}', bus='{}'",
                            alert.name(), alert.id(), eventBus, error);
                    return Mono.empty();
                })
                .then();
    }

    private Mono<PutEventsResponse> putEvent(PutEventsRequestEntry entry, Alert alert) {
        var request = PutEventsRequest.builder().entries(entry).build();
        return Mono.fromCallable(() -> client.get().putEvents(request))
                .flatMap(response -> response.failedEntryCount() > 0
                        ? Mono.error(new FailedEntryException(response.entries().getFirst()))
                        : Mono.just(response))
                .retryWhen(Retry.backoff(MAX_RETRIES, RETRY_BACKOFF)
                        .jitter(0.5)
                        .filter(AlertEventBridgePublisher::isRetryable)
                        .doBeforeRetry(signal -> log.warn(
                                "Retrying EventBridge publish for alertId='{}', attempt='{}'",
                                alert.id(), signal.totalRetries() + 1, signal.failure()))
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }

    private static boolean isRetryable(Throwable throwable) {
        return switch (throwable) {
            case FailedEntryException failed -> RETRYABLE_ENTRY_ERROR_CODES.contains(failed.getErrorCode());
            case AwsServiceException aws -> aws.isThrottlingException() || aws.statusCode() >= 500;
            default -> false;
        };
    }

    private PutEventsRequestEntry buildEntry(Alert alert, String workspaceId, String workspaceName,
            AlertEventType eventType, Map<String, Object> payload, Instant triggeredAt) {
        Map<String, Object> product = new HashMap<>(payload);
        @SuppressWarnings("unchecked")
        List<String> metadata = (List<String>) payload.getOrDefault("metadata", List.of());
        product.put("metadata", AlertPayloadAdapter.deserializeMetadata(metadata, eventType));

        var detail = AlertEventBridgeDetail.builder()
                .schemaVersion(SCHEMA_VERSION)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .alertId(alert.id())
                .alertName(alert.name())
                .eventType(eventType.getValue())
                .url(buildAlertUrl(alert, workspaceName))
                .triggeredAt(triggeredAt)
                .opik(product)
                .build();

        return PutEventsRequestEntry.builder()
                .eventBusName(eventBus)
                .source(SOURCE)
                .detailType(DETAIL_TYPE)
                .time(triggeredAt)
                .detail(serializeWithinLimit(detail))
                .build();
    }

    private String serializeWithinLimit(AlertEventBridgeDetail detail) {
        String json = JsonUtils.writeValueAsString(detail);
        int size = json.getBytes(StandardCharsets.UTF_8).length;
        if (size <= MAX_DETAIL_BYTES) {
            return json;
        }

        log.warn("EventBridge detail for alertId='{}' is '{}' bytes, dropping metadata and capping lists",
                detail.alertId(), size);

        Map<String, Object> product = new HashMap<>(detail.opik());
        product.put("metadata", List.of());
        product.computeIfPresent("eventIds", (key, value) -> capList(value));
        product.computeIfPresent("userNames", (key, value) -> capList(value));

        return JsonUtils.writeValueAsString(detail.toBuilder().truncated(true).opik(product).build());
    }

    private static List<?> capList(Object value) {
        List<?> list = (List<?>) value;
        return list.subList(0, Math.min(list.size(), MAX_TRUNCATED_LIST_SIZE));
    }

    // Base URL is the UI origin the alert was saved from, as the Slack payload uses for its links
    private static String buildAlertUrl(Alert alert, String workspaceName) {
        String baseUrl = Optional.ofNullable(alert.metadata())
                .map(metadata -> metadata.get(SlackWebhookPayloadMapper.BASE_URL_METADATA_KEY))
                .orElse(null);
        if (StringUtils.isBlank(baseUrl) || alert.projectId() == null || StringUtils.isBlank(workspaceName)) {
            return null;
        }

        return (baseUrl.endsWith("/") ? baseUrl : baseUrl + "/") + workspaceName + "/projects/" + alert.projectId()
                + "/alerts/" + alert.id();
    }

    private static Attributes metricAttributes(String workspaceId, String workspaceName, String result) {
        return Attributes.of(
                WORKSPACE_ID_KEY, workspaceId,
                WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, workspaceId),
                RESULT_KEY, result);
    }

    @Builder(toBuilder = true)
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    record AlertEventBridgeDetail(
            @NonNull String schemaVersion,
            @NonNull String workspaceId,
            String workspaceName,
            @NonNull UUID alertId,
            @NonNull String alertName,
            @NonNull String eventType,
            String url,
            @NonNull Instant triggeredAt,
            boolean truncated,
            @NonNull Map<String, Object> opik) {
    }

    @Getter
    static class FailedEntryException extends RuntimeException {
        private final String errorCode;

        FailedEntryException(PutEventsResultEntry entry) {
            super("EventBridge rejected the entry, errorCode='%s', errorMessage='%s'"
                    .formatted(entry.errorCode(), entry.errorMessage()));
            this.errorCode = entry.errorCode();
        }
    }
}
