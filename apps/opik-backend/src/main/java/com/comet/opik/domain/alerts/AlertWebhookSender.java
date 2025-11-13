package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookPublisher;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Service responsible for creating and sending webhook notifications for alerts.
 *
 * Handles webhook creation, validation, payload formatting, and delivery
 * for both event-based and metrics-based alerts.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AlertWebhookSender {

    private final @NonNull WebhookPublisher webhookPublisher;
    private final @NonNull WorkspaceNameService workspaceNameService;
    private final @NonNull OpikConfiguration config;

    /**
     * Creates a consolidated webhook event and sends it via WebhookPublisher.
     *
     * @param alert the alert configuration
     * @param workspaceId the workspace ID
     * @param workspaceName the workspace name
     * @param eventType the event type
     * @param eventIds the set of aggregated event IDs
     * @param payloads the set of aggregated event payloads
     * @param userNames the set of user names who triggered events
     * @return Mono that completes when the webhook is sent
     */
    public Mono<Void> createAndSendWebhook(
            @NonNull Alert alert,
            @NonNull String workspaceId,
            @NonNull String workspaceName,
            @NonNull AlertEventType eventType,
            @NonNull List<String> eventIds,
            @NonNull List<String> payloads,
            @NonNull List<String> userNames) {

        if (Boolean.FALSE.equals(alert.enabled())) {
            log.warn("Alert '{}' is disabled, skipping webhook", alert.id());
            return Mono.empty();
        }

        if (StringUtils.isEmpty(alert.webhook().url())) {
            log.warn("Alert '{}' has no webhook configuration, skipping", alert.id());
            return Mono.empty();
        }

        log.debug("Creating consolidated webhook event for alert '{}' with '{}' events and '{}' payloads",
                alert.id(), eventIds.size(), payloads.size());

        // Create payload with alert and aggregation data
        Map<String, Object> payload = Map.of(
                "alertId", alert.id().toString(),
                "alertName", alert.name(),
                "eventType", eventType.getValue(),
                "eventIds", eventIds,
                "metadata", payloads,
                "userNames", userNames,
                "eventCount", eventIds.size(),
                "aggregationType", "consolidated",
                "message", String.format("Alert '%s': %d %s events aggregated",
                        alert.name(), eventIds.size(), eventType.getValue()));

        log.info("Sending webhook for alertName='{}', alertId='{}', eventCount='{}', payloadCount='{}'",
                alert.name(), alert.id(), eventIds.size(), payloads.size());

        // Send via WebhookPublisher with data from alert configuration
        return webhookPublisher.publishWebhookEvent(
                eventType,
                alert,
                workspaceId,
                StringUtils.isBlank(workspaceName)
                        ? workspaceNameService.getWorkspaceName(workspaceId,
                                config.getAuthentication().getReactService().url())
                        : workspaceName,
                payload,
                config.getWebhook().getMaxRetries())
                .doOnSuccess(webhookId -> log.info(
                        "Successfully sent webhook for alertName='{}', alertId='{}': webhook_id='{}' ",
                        alert.name(), alert.id(), webhookId))
                .doOnError(error -> log.error("Failed to send webhook for alertName='{}',alertId='{}':",
                        alert.name(), alert.id(), error))
                .then();
    }
}
