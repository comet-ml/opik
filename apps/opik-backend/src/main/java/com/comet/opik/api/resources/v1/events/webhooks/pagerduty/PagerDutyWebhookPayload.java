package com.comet.opik.api.resources.v1.events.webhooks.pagerduty;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

/**
 * Represents a PagerDuty webhook payload with Events API v2 structure.
 *
 * @see <a href="https://developer.pagerduty.com/docs/ZG9jOjExMDI5NTgw-events-api-v2-overview">PagerDuty Events API v2</a>
 */
@Builder(toBuilder = true)
public record PagerDutyWebhookPayload(
        @NonNull @JsonProperty("routing_key") String routingKey,
        @NonNull @JsonProperty("event_action") String eventAction,
        @NonNull @JsonProperty("dedup_key") String dedupKey,
        @NonNull @JsonProperty("payload") PagerDutyPayload payload) {
}
