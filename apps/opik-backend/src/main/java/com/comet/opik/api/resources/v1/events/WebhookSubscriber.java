package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_ID;

/**
 * Service responsible for sending webhook events to external HTTP endpoints.
 * This service ONLY sends webhooks and does not handle aggregation or debouncing.
 * Event aggregation and debouncing is handled by WebhookEventAggregationService.
 */
@EagerSingleton
@Slf4j
public class WebhookSubscriber extends BaseRedisSubscriber<WebhookEvent<?>> {

    private static final String METRICS_BASE_NAME = "webhook";
    private static final String METRICS_NAMESPACE = "opik.webhook";

    private final WebhookHttpClient webhookHttpClient;
    private final WebhookConfig webhookConfig;

    private static final TypeReference<List<Prompt>> LIST_PROMPT_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<Prompt> PROMPT_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<PromptVersion> PROMPT_VERSION_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<List<Trace>> LIST_TRACE_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<List<FeedbackScoreItem.FeedbackScoreBatchItem>> LIST_TRACE_SCORE_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<List<FeedbackScoreItem.FeedbackScoreBatchItemThread>> LIST_THREAD_SCORE_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<List<Guardrail>> LIST_GUARDRAIL_TYPE_REFERENCE = new TypeReference<>() {
    };

    private final LongCounter webhookEventProcessedCounter;

    @Inject
    public WebhookSubscriber(@NonNull WebhookConfig webhookConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull WebhookHttpClient webhookHttpClient) {
        super(webhookConfig, redisson, METRICS_BASE_NAME, WebhookConfig.PAYLOAD_FIELD);
        this.webhookHttpClient = webhookHttpClient;
        this.webhookConfig = webhookConfig;

        // Pre-build metrics during initialization
        this.webhookEventProcessedCounter = meter
                .counterBuilder("opik_webhook_events_processed_total")
                .setDescription("Total number of webhook events processed")
                .build();
    }

    @Override
    protected String getMetricNamespace() {
        return METRICS_NAMESPACE;
    }

    @Override
    protected Mono<Void> processEvent(@NonNull WebhookEvent<?> event) {
        log.debug("Processing webhook event: id='{}', type='{}', url='{}'",
                event.getId(), event.getEventType(), event.getUrl());

        // Build attributes synchronously (lightweight, non-blocking operation)
        var attributes = Attributes.builder()
                .put("event_type", event.getEventType().getValue())
                .put("workspace_id", event.getWorkspaceId())
                .build();

        return validateEvent(event)
                .flatMap(unused -> {
                    @SuppressWarnings("unchecked")
                    WebhookEvent<Map<String, Object>> webhookEvent = (WebhookEvent<Map<String, Object>>) event;

                    return Mono.fromCallable(() -> deserializeEventPayload(webhookEvent))
                            .subscribeOn(Schedulers.parallel())
                            .flatMap(webhookHttpClient::sendWebhook)
                            .doOnSuccess(unused2 -> {
                                log.info("Successfully sent webhook: id='{}', type='{}', url='{}'",
                                        event.getId(), event.getEventType(), event.getUrl());

                                // Record success metrics
                                webhookEventProcessedCounter.add(1,
                                        attributes.toBuilder().put("status", "success").build());
                            })
                            .onErrorResume(
                                    throwable -> handlePermanentFailure(event, throwable).then(Mono.empty()));
                })
                .contextWrite(ctx -> ctx.put(WORKSPACE_ID, event.getWorkspaceId()))
                .then();
    }

    private Mono<Void> handlePermanentFailure(@NonNull WebhookEvent<?> event, @NonNull Throwable throwable) {
        log.error("Webhook event '{}' permanently failed after all retries. " +
                "Event type: '{}', URL: '{}', Error: '{}'",
                event.getId(), event.getEventType(), event.getUrl(), throwable.getMessage());

        // TODO: Implement dead letter queue or notification mechanism for permanent failures
        // For now, we'll just log the failure and continue processing other events

        // Record permanent failure metrics
        var attributes = Attributes.builder()
                .put("event_type", event.getEventType().getValue())
                .put("workspace_id", event.getWorkspaceId())
                .put("retry_count", event.getMaxRetries())
                .put("status", "permanent_failure")
                .build();

        meter.counterBuilder("opik_webhook_events_permanent_failures_total")
                .setDescription("Total number of webhook events that permanently failed")
                .build()
                .add(1, attributes);

        return Mono.empty();
    }

    private Mono<Void> validateEvent(@NonNull WebhookEvent<?> event) {
        return Mono.fromRunnable(() -> {
            if (event.getUrl() == null || event.getUrl().trim().isEmpty()) {
                throw new IllegalArgumentException("Webhook URL cannot be null or empty");
            }

            if (event.getId() == null || event.getId().trim().isEmpty()) {
                throw new IllegalArgumentException("Webhook event ID cannot be null or empty");
            }

            if (event.getEventType() == null) {
                throw new IllegalArgumentException("Webhook event type cannot be null");
            }

            if (!event.getUrl().startsWith("http://") && !event.getUrl().startsWith("https://")) {
                throw new IllegalArgumentException("Webhook URL must start with http:// or https://");
            }

            log.debug("Webhook event validation passed for event: '{}'", event.getId());
        }).then();
    }

    public static WebhookEvent<Map<String, Object>> deserializeEventPayload(
            @NonNull WebhookEvent<Map<String, Object>> event) {
        Map<String, Object> payload = event.getPayload();
        @SuppressWarnings("unchecked")
        List<String> metadatas = (List<String>) payload.getOrDefault("metadata", List.of());

        var deserializeMetadata = metadatas.stream()
                .map(metadata -> JsonUtils.readValue(metadata, payloadTypePerEventType(event.getEventType())))
                .toList();

        Map<String, Object> updatedPayload = new HashMap<>(payload);
        updatedPayload.put("metadata", deserializeMetadata);

        return event.toBuilder()
                .payload(updatedPayload)
                .build();
    }

    private static TypeReference<?> payloadTypePerEventType(AlertEventType eventType) {
        return switch (eventType) {
            case PROMPT_DELETED -> LIST_PROMPT_TYPE_REFERENCE;
            case PROMPT_CREATED -> PROMPT_TYPE_REFERENCE;
            case PROMPT_COMMITTED -> PROMPT_VERSION_TYPE_REFERENCE;
            case TRACE_ERRORS -> LIST_TRACE_TYPE_REFERENCE;
            case TRACE_FEEDBACK_SCORE -> LIST_TRACE_SCORE_TYPE_REFERENCE;
            case TRACE_THREAD_FEEDBACK_SCORE -> LIST_THREAD_SCORE_TYPE_REFERENCE;
            case TRACE_GUARDRAILS_TRIGGERED -> LIST_GUARDRAIL_TYPE_REFERENCE;
        };
    }

    @Override
    public void start() {
        if (!webhookConfig.isEnabled()) {
            log.info("Webhook subscriber is disabled, skipping startup");
            return;
        }

        log.info("Starting webhook subscriber with config: maxRetries={}, requestTimeout={}, connectionTimeout={}",
                webhookConfig.getMaxRetries(),
                webhookConfig.getRequestTimeout(),
                webhookConfig.getConnectionTimeout());

        super.start();
    }

    @Override
    public void stop() {
        if (!webhookConfig.isEnabled()) {
            log.info("Webhook subscriber is disabled, skipping shutdown");
            return;
        }

        log.info("Stopping webhook subscriber");
        super.stop();
    }
}
