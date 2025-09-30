package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_ID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_ID;

/**
 * Service responsible for sending webhook events to external HTTP endpoints.
 * This service ONLY sends webhooks and does not handle aggregation or debouncing.
 * Event aggregation and debouncing is handled by WebhookEventAggregationService.
 */
@Singleton
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

        return Mono.defer(() -> validateEvent(event))
                .then(Mono.defer(() -> webhookHttpClient.sendWebhook(event)))
                .contextWrite(ctx -> ctx.put(WORKSPACE_ID, event.getWorkspaceId())
                        .put(RequestContext.USER_NAME, event.getUserName()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(unused -> {
                    log.info("Successfully sent webhook: id='{}', type='{}', url='{}'",
                            event.getId(), event.getEventType(), event.getUrl());
                })
                .doOnError(throwable -> {
                    log.error("Failed to send webhook: id='{}', type='{}', url='{}', error='{}'",
                            event.getId(), event.getEventType(), event.getUrl(),
                            throwable.getMessage(), throwable);
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

}
