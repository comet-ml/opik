package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.util.Map;

import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * Reactive HTTP client for sending webhook requests with retry capabilities.
 */
@Slf4j
@Singleton
public class WebhookHttpClient {

    private final @NonNull Client httpClient;
    private final @NonNull WebhookConfig webhookConfig;
    private final Logger userFacingLog;

    @Inject
    public WebhookHttpClient(@NonNull Client httpClient, @NonNull WebhookConfig webhookConfig) {
        this.httpClient = httpClient;
        this.webhookConfig = webhookConfig;
        this.userFacingLog = UserFacingLoggingFactory.getLogger(this.getClass());
    }

    /**
     * Sends a webhook event to the specified URL with retry logic.
     *
     * @param event The webhook event to send
     * @return A Mono that completes when the webhook is successfully sent or fails permanently
     */
    public Mono<Void> sendWebhook(@NonNull WebhookEvent<?> event) {
        log.info("Sending webhook event '{}' to URL: '{}'", event.getId(), event.getUrl());

        return Mono.defer(() -> performWebhookRequest(event))
                .doOnSuccess(response -> {
                    logInfo(event, "Webhook '{}' sent successfully. Status: '{}'", event.getId(), response.getStatus());
                    response.close(); // Ensure response is closed to free resources
                })
                .retryWhen(createRetrySpec())
                .doOnError(throwable -> logError(event,
                        "Webhook '%s' permanently failed after all retries".formatted(event.getId()), throwable))
                .then();
    }

    private void logInfo(WebhookEvent<?> event, String message, Object... args) {
        try (var logContext = wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.WEBHOOK_EVENT_HANDLER.name(),
                "workspace_id", event.getWorkspaceId(),
                "webhook_id", event.getId(),
                "alert_id", event.getAlertId().toString()))) {
            userFacingLog.info(message, args);
        }
    }

    private void logError(WebhookEvent<?> event, String message, Throwable throwable) {
        try (var logContext = wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.WEBHOOK_EVENT_HANDLER.name(),
                "workspace_id", event.getWorkspaceId(),
                "webhook_id", event.getId(),
                "alert_id", event.getAlertId().toString()))) {
            userFacingLog.error(message, throwable);
        }
    }

    private Mono<Response> performWebhookRequest(@NonNull WebhookEvent<?> event) {
        return Mono.<Response>create(sink -> {
            try {
                var target = httpClient.target(event.getUrl());
                var requestBuilder = target.request(MediaType.APPLICATION_JSON);

                // Add custom headers if present
                if (MapUtils.isNotEmpty(event.getHeaders())) {
                    event.getHeaders().forEach(requestBuilder::header);
                }

                // Add default headers
                requestBuilder.header("User-Agent", "Opik-Webhook/1.0");
                requestBuilder.header("Content-Type", MediaType.APPLICATION_JSON);

                // Serialize payload using JsonUtils.MAPPER to handle Instant fields properly
                var jsonPayload = JsonUtils.writeValueAsString(event);
                var entity = Entity.entity(jsonPayload, MediaType.APPLICATION_JSON);

                // Set timeout properties
                requestBuilder.property("jersey.config.client.connectTimeout",
                        (int) webhookConfig.getConnectionTimeout().toMilliseconds());
                requestBuilder.property("jersey.config.client.readTimeout",
                        (int) webhookConfig.getRequestTimeout().toMilliseconds());

                requestBuilder.async().post(entity, new InvocationCallback<Response>() {
                    @Override
                    public void completed(Response response) {
                        if (isSuccessfulResponse(response)) {
                            sink.success(response);
                        } else {
                            String errorBody = readErrorBody(response);
                            sink.error(new RetryUtils.RetryableHttpException(
                                    "Webhook failed with status %d: %s".formatted(
                                            response.getStatus(), errorBody),
                                    response.getStatus()));
                        }
                    }

                    @Override
                    public void failed(Throwable throwable) {
                        sink.error(throwable);
                    }
                });

            } catch (Exception exception) {
                sink.error(exception);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean isSuccessfulResponse(Response response) {
        return Response.Status.Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily());
    }

    private String readErrorBody(@NonNull Response response) {
        try {
            response.bufferEntity();
            return response.readEntity(String.class);
        } catch (Exception exception) {
            log.warn("Failed to read error response body", exception);
            return "Unable to read response body";
        }
    }

    private Retry createRetrySpec() {
        return RetryUtils.handleHttpErrors(
                webhookConfig.getMaxRetries(),
                webhookConfig.getInitialRetryDelay().toJavaDuration(),
                webhookConfig.getMaxRetryDelay().toJavaDuration());
    }
}
