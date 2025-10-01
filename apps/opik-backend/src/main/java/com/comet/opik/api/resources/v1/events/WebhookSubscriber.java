package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.opentelemetry.api.common.Attributes;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_ID;

/**
 * Reactive Redis subscriber that processes webhook events from a Redis stream
 * and sends them to external HTTP endpoints with retry capabilities.
 */
@EagerSingleton
@Slf4j
public class WebhookSubscriber extends BaseRedisSubscriber<WebhookEvent<?>> {

    private static final String METRICS_BASE_NAME = "webhook";
    private static final String METRICS_NAMESPACE = "opik.webhook";

    private final WebhookHttpClient webhookHttpClient;
    private final WebhookConfig webhookConfig;

    @Inject
    public WebhookSubscriber(@NonNull WebhookConfig webhookConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull WebhookHttpClient webhookHttpClient) {
        super(webhookConfig, redisson, METRICS_BASE_NAME, WebhookConfig.PAYLOAD_FIELD);
        this.webhookHttpClient = webhookHttpClient;
        this.webhookConfig = webhookConfig;
    }

    @Override
    protected String getMetricNamespace() {
        return METRICS_NAMESPACE;
    }

    @Override
    protected Mono<Void> processEvent(@NonNull WebhookEvent<?> event) {
        log.debug("Processing webhook event: id='{}', type='{}', url='{}'",
                event.getId(), event.getEventType(), event.getUrl());

        // Record metrics
        var attributes = Attributes.builder()
                .put("event_type", event.getEventType().getValue())
                .put("workspace_id", event.getWorkspaceId())
                .build();

        return Mono.defer(() -> validateEvent(event))
                .then(Mono.defer(() -> webhookHttpClient.sendWebhook(event)))
                .contextWrite(ctx -> ctx.put(WORKSPACE_ID, event.getWorkspaceId())
                        .put(RequestContext.USER_NAME, event.getUserName()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(unused -> {
                    log.info("Successfully processed webhook event: id='{}', type='{}', url='{}'",
                            event.getId(), event.getEventType(), event.getUrl());

                    // Record success metrics
                    meter.counterBuilder("opik_webhook_events_processed_total")
                            .setDescription("Total number of webhook events processed")
                            .build()
                            .add(1, attributes.toBuilder().put("status", "success").build());
                })
                .doOnError(throwable -> {
                    log.error("Failed to process webhook event: id='{}', type='{}', url='{}', error='{}'",
                            event.getId(), event.getEventType(), event.getUrl(), throwable.getMessage(), throwable);

                    // Record failure metrics
                    meter.counterBuilder("opik_webhook_events_processed_total")
                            .setDescription("Total number of webhook events processed")
                            .build()
                            .add(1, attributes.toBuilder().put("status", "failure").build());
                })
                .onErrorResume(throwable -> {
                    // Handle permanent failures - could implement dead letter queue here
                    return handlePermanentFailure(event, throwable);
                });
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
        });
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
