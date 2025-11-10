package com.comet.opik.api.resources.v1.events.webhooks.pagerduty;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Maps webhook events to PagerDuty-specific payload format with Events API v2 structure.
 */
@Slf4j
@UtilityClass
public class PagerDutyWebhookPayloadMapper {

    public static final String ROUTING_KEY_METADATA_KEY = "routing_key";
    private static final String DEFAULT_ROUTING_KEY = "default-routing-key";
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    /**
     * Converts a webhook event to PagerDuty webhook payload.
     *
     * @param event the webhook event to convert
     * @return the PagerDuty webhook payload
     */
    public static PagerDutyWebhookPayload toPagerDutyPayload(@NonNull WebhookEvent<Map<String, Object>> event) {
        log.debug("Mapping webhook event to PagerDuty payload: eventType='{}'", event.getEventType());

        String severity = determineSeverity(event.getEventType());
        String timestamp = event.getCreatedAt().atOffset(ZoneOffset.UTC).format(ISO_FORMATTER);

        return PagerDutyWebhookPayload.builder()
                .routingKey(event.getAlertMetadata().getOrDefault(ROUTING_KEY_METADATA_KEY, DEFAULT_ROUTING_KEY))
                .eventAction("trigger")
                .dedupKey(event.getId())
                .payload(PagerDutyPayload.builder()
                        .summary(event.getAlertName())
                        .source("Opik")
                        .severity(severity)
                        .timestamp(timestamp)
                        .customDetails(event.toBuilder().url(null).headers(null).secret(null).alertType(null)
                                .jsonPayload(null).alertMetadata(null).build())
                        .build())
                .build();
    }

    private static String determineSeverity(@NonNull AlertEventType eventType) {
        return switch (eventType) {
            case TRACE_ERRORS -> "error";
            default -> "info";
        };
    }
}
