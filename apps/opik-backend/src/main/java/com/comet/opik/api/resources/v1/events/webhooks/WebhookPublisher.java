package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.events.webhooks.WebhookEventTypes;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.WebhookConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.api.stream.StreamAddArgs;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Service for publishing webhook events to the Redis stream.
 * This allows other parts of the application to trigger webhook calls.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class WebhookPublisher {

    private final @NonNull RedissonReactiveClient redisson;
    private final @NonNull WebhookConfig webhookConfig;
    private final @NonNull IdGenerator idGenerator;

    /**
     * Publishes a webhook event to the Redis stream for processing.
     *
     * @param eventType    The type of event
     * @param workspaceId  The workspace ID associated with the event
     * @param webhookUrl   The URL to send the webhook to
     * @param payload      The payload to include in the webhook
     * @param headers      Optional custom headers to include in the HTTP request
     * @return A Mono that completes when the event is published to the stream
     */
    public <T> Mono<String> publishWebhookEvent(@NonNull WebhookEventTypes eventType,
            @NonNull String workspaceId,
            @NonNull String webhookUrl,
            @NonNull T payload,
            Map<String, String> headers) {
        return publishWebhookEvent(eventType, workspaceId, webhookUrl, payload, headers, webhookConfig.getMaxRetries());
    }

    /**
     * Publishes a webhook event to the Redis stream for processing with custom retry count.
     *
     * @param eventType    The type of event
     * @param workspaceId  The workspace ID associated with the event
     * @param webhookUrl   The URL to send the webhook to
     * @param payload      The payload to include in the webhook
     * @param headers      Optional custom headers to include in the HTTP request
     * @param maxRetries   Maximum number of retry attempts for this specific event
     * @return A Mono that completes when the event is published to the stream
     */
    public <T> Mono<String> publishWebhookEvent(@NonNull WebhookEventTypes eventType,
            @NonNull String workspaceId,
            @NonNull String webhookUrl,
            @NonNull T payload,
            Map<String, String> headers,
            int maxRetries) {

        if (!webhookConfig.isEnabled()) {
            log.debug("Webhook publishing is disabled, ignoring event: type='{}', workspace='{}'",
                    eventType, workspaceId);
            return Mono.empty();
        }

        String eventId = idGenerator.generateId().toString();

        var webhookEvent = WebhookEvent.builder()
                .id(eventId)
                .url(webhookUrl)
                .eventType(eventType)
                .payload(payload)
                .headers(headers != null ? headers : Map.of())
                .maxRetries(maxRetries)
                .workspaceId(workspaceId)
                .build();

        log.info("Publishing webhook event: id='{}', type='{}', workspace='{}', url='{}'",
                eventId, eventType, workspaceId, webhookUrl);

        return Mono.defer(() -> {
            RStreamReactive<String, WebhookEvent<?>> stream = redisson.getStream(
                    webhookConfig.getStreamName(),
                    webhookConfig.getCodec());

            return stream.add(StreamAddArgs.entry(WebhookConfig.PAYLOAD_FIELD, webhookEvent))
                    .map(streamMessageId -> {
                        log.debug("Webhook event published successfully: id='{}', streamMessageId='{}'",
                                eventId, streamMessageId);
                        return eventId;
                    })
                    .doOnError(throwable -> {
                        log.error("Failed to publish webhook event: id='{}', type='{}', error='{}'",
                                eventId, eventType, throwable.getMessage(), throwable);
                    });
        })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
