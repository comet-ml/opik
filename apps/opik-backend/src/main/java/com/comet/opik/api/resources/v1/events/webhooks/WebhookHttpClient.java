package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.utils.RetryUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.util.Map;
import java.util.Optional;

import static com.comet.opik.infrastructure.EncryptionUtils.decrypt;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * Reactive HTTP client for sending webhook requests with retry capabilities.
 */
@Slf4j
@Singleton
public class WebhookHttpClient {

    private static final String USER_AGENT_VALUE = "Opik-Webhook/1.0";
    public static final String BEARER_PREFIX = "Bearer ";

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
     * @return A Mono that completes with the response body or "ok" when the webhook is successfully sent or fails permanently
     */
    public Mono<String> sendWebhook(@NonNull WebhookEvent<?> event) {
        log.info("Sending webhook event '{}' to URL: '{}', max retries: '{}'",
                event.getId(), event.getUrl(), event.getMaxRetries());

        // Serialize payload asynchronously (non-blocking JSON serialization)
        return Mono.deferContextual(
                ctx -> performWebhookRequest(event, event.getJsonPayload(), ctx.get(RequestContext.WORKSPACE_ID))
                        .retryWhen(createRetrySpec(event.getId(), event.getMaxRetries()))
                        .doOnError(throwable -> logError(event,
                                ctx.get(RequestContext.WORKSPACE_ID),
                                "Webhook '%s' permanently failed after all retries".formatted(event.getId()),
                                throwable)));
    }

    private void logInfo(WebhookEvent<?> event, String workspaceId, String message, Object... args) {
        try (var logContext = wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.ALERT_EVENT.name(),
                UserLog.WORKSPACE_ID, workspaceId,
                UserLog.EVENT_ID, event.getId(),
                UserLog.ALERT_ID, event.getAlertId().toString()))) {
            userFacingLog.info(message, args);
        }
    }

    private void logError(WebhookEvent<?> event, String workspaceId, String message, Throwable throwable) {
        try (var logContext = wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.ALERT_EVENT.name(),
                UserLog.WORKSPACE_ID, workspaceId,
                UserLog.EVENT_ID, event.getId(),
                UserLog.ALERT_ID, event.getAlertId().toString()))) {
            userFacingLog.error(message, throwable);
        }
    }

    private Mono<String> performWebhookRequest(WebhookEvent<?> event, String jsonPayload,
            String workspaceId) {
        return Mono.<String>create(sink -> {
            try {
                var target = httpClient.target(event.getUrl());
                var requestBuilder = target.request(MediaType.APPLICATION_JSON);

                // Add custom headers if present
                if (MapUtils.isNotEmpty(event.getHeaders())) {
                    event.getHeaders().forEach(requestBuilder::header);
                }

                // Add default headers
                requestBuilder.header(HttpHeaders.USER_AGENT, USER_AGENT_VALUE);
                requestBuilder.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON);

                if (StringUtils.isNotBlank(event.getSecret())) {
                    requestBuilder.header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + decrypt(event.getSecret()));
                }

                // Set timeout properties
                requestBuilder.property("jersey.config.client.connectTimeout",
                        (int) webhookConfig.getConnectionTimeout().toMilliseconds());
                requestBuilder.property("jersey.config.client.readTimeout",
                        (int) webhookConfig.getRequestTimeout().toMilliseconds());

                var entity = Entity.entity(jsonPayload, MediaType.APPLICATION_JSON);

                // Send async request
                requestBuilder.async().post(entity, new InvocationCallback<Response>() {
                    @Override
                    public void completed(Response response) {
                        try (response) {
                            if (isSuccessfulResponse(response)) {
                                logInfo(event, workspaceId, "Webhook '{}' sent successfully. Status: '{}'",
                                        event.getId(), response.getStatus());
                                var responseBody = readResponseBody(response);
                                // Return body if present, otherwise return "ok"
                                sink.success(responseBody.orElse("ok"));
                            } else {
                                var responseBody = readResponseBody(response);
                                String errorMessage = responseBody
                                        .map(body -> "Webhook failed with status %d: %s".formatted(response.getStatus(),
                                                body))
                                        .orElseGet(
                                                () -> "Webhook failed with status %d".formatted(response.getStatus()));
                                sink.error(new RetryUtils.RetryableHttpException(
                                        errorMessage,
                                        response.getStatus()));
                            }
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
        })
                .doFinally(signalType -> {
                    log.debug("Webhook '{}' request completed with signal: '{}'", event.getId(), signalType);
                });
    }

    private boolean isSuccessfulResponse(Response response) {
        return Response.Status.Family.SUCCESSFUL.equals(response.getStatusInfo().getFamily());
    }

    private Optional<String> readResponseBody(Response response) {
        try {
            // Only read if entity exists and has content
            if (!response.hasEntity()) {
                return Optional.empty();
            }

            response.bufferEntity();
            String body = response.readEntity(String.class);
            return StringUtils.isNotBlank(body) ? Optional.of(body) : Optional.empty();
        } catch (Exception exception) {
            log.debug("Failed to read response body for webhook", exception);
            return Optional.empty();
        }
    }

    private Retry createRetrySpec(String eventId, int maxRetries) {
        return RetryUtils.handleHttpErrors(
                maxRetries,
                webhookConfig.getInitialRetryDelay().toJavaDuration(),
                webhookConfig.getMaxRetryDelay().toJavaDuration())
                .doBeforeRetry(retrySignal -> {
                    int attemptNumber = (int) retrySignal.totalRetries() + 1;
                    Throwable error = retrySignal.failure();

                    log.warn("Retrying webhook '{}' - Attempt: {}/{}, Error: '{}'",
                            eventId, attemptNumber, maxRetries, error.getMessage());
                });
    }
}
