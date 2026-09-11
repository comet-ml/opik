package com.comet.opik.api.resources.v1.events.webhooks.pagerduty;

import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

/**
 * Represents the payload section of a PagerDuty event.
 *
 * @see <a href="https://developer.pagerduty.com/docs/ZG9jOjExMDI5NTgw-events-api-v2-overview">PagerDuty Events API v2</a>
 */
@Builder(toBuilder = true)
public record PagerDutyPayload(
        @NonNull @JsonProperty("summary") String summary,
        @NonNull @JsonProperty("source") String source,
        @NonNull @JsonProperty("severity") String severity,
        @NonNull @JsonProperty("timestamp") String timestamp,
        @JsonProperty("custom_details") WebhookEvent<?> customDetails) {
}
