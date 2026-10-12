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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service responsible for creating and sending webhook notifications for alerts.
 * Every triggered alert is also published to AWS EventBridge when that is enabled, even without a webhook URL.
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
    private final @NonNull AlertEventBridgePublisher eventBridgePublisher;

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
     * @param triggerKey identifies this trigger across reprocessing; the EventBridge eventId is derived from it
     * @return Mono that completes when the webhook is sent
     */
    public Mono<Void> createAndSendWebhook(
            @NonNull Alert alert,
            @NonNull String workspaceId,
            @NonNull String workspaceName,
            @NonNull AlertEventType eventType,
            @NonNull List<String> eventIds,
            @NonNull List<String> payloads,
            @NonNull List<String> userNames,
            @NonNull String triggerKey) {

        if (Boolean.FALSE.equals(alert.enabled())) {
            log.warn("Alert '{}' is disabled, skipping webhook", alert.id());
            return Mono.empty();
        }

        boolean hasWebhookUrl = StringUtils.isNotEmpty(alert.webhook().url());
        if (!hasWebhookUrl && !eventBridgePublisher.isEnabled()) {
            log.warn("Alert '{}' has no webhook configuration, skipping", alert.id());
            return Mono.empty();
        }

        String resolvedWorkspaceName = workspaceName;
        RuntimeException workspaceNameFailure = null;
        if (StringUtils.isBlank(workspaceName)) {
            try {
                resolvedWorkspaceName = workspaceNameService.getWorkspaceName(workspaceId,
                        config.getAuthentication().getReactService().url());
            } catch (RuntimeException exception) {
                // EventBridge still gets the event without a name; the webhook keeps failing on it as before, and
                // the job logs that failure
                log.debug("Failed to resolve workspace name for workspaceId='{}', alertId='{}'",
                        workspaceId, alert.id(), exception);
                resolvedWorkspaceName = null;
                workspaceNameFailure = exception;
            }
        }
        String eventBridgeWorkspaceName = resolvedWorkspaceName;
        Map<String, Object> payload = buildPayload(alert, eventType, eventIds, payloads, userNames);

        if (eventBridgePublisher.isEnabled()) {
            // Fire-and-forget: EventBridge retries must not hold up, or fail, the webhook delivery. The eventId is
            // derived from the trigger, so reprocessing a trigger and every retry inside publish reuse it.
            Mono.defer(() -> eventBridgePublisher.publish(alert, eventBridgeEventId(triggerKey), workspaceId,
                    eventBridgeWorkspaceName, eventType, payload))
                    .onErrorResume(error -> {
                        log.error("Failed to publish alert '{}' to EventBridge", alert.id(), error);
                        return Mono.empty();
                    })
                    .subscribe();
        }

        if (!hasWebhookUrl) {
            log.warn("Alert '{}' has no webhook configuration, skipping webhook", alert.id());
            if (workspaceNameFailure != null) {
                // Nothing is rethrown on this path, so this is the only place the failure gets logged
                log.warn("Failed to resolve workspace name for workspaceId='{}', alertId='{}'",
                        workspaceId, alert.id(), workspaceNameFailure);
            }
            return Mono.empty();
        }

        if (workspaceNameFailure != null) {
            throw workspaceNameFailure;
        }

        log.info("Sending webhook for alertName='{}', alertId='{}', eventCount='{}', payloadCount='{}'",
                alert.name(), alert.id(), eventIds.size(), payloads.size());

        // Send via WebhookPublisher with data from alert configuration
        return webhookPublisher.publishWebhookEvent(
                eventType,
                alert,
                workspaceId,
                resolvedWorkspaceName,
                payload,
                config.getWebhook().getMaxRetries())
                .doOnSuccess(webhookId -> log.info(
                        "Successfully sent webhook for alertName='{}', alertId='{}': webhook_id='{}' ",
                        alert.name(), alert.id(), webhookId))
                .doOnError(error -> log.error("Failed to send webhook for alertName='{}',alertId='{}':",
                        alert.name(), alert.id(), error))
                .then();
    }

    private Map<String, Object> buildPayload(Alert alert, AlertEventType eventType, List<String> eventIds,
            List<String> payloads, List<String> userNames) {
        log.debug("Creating consolidated webhook event for alert '{}' with '{}' events and '{}' payloads",
                alert.id(), eventIds.size(), payloads.size());

        return Map.of(
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
    }

    static String eventBridgeEventId(String triggerKey) {
        return UUID.nameUUIDFromBytes(("opik-alert:" + triggerKey).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
