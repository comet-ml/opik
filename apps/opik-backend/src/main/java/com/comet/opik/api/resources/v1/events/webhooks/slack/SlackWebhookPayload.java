package com.comet.opik.api.resources.v1.events.webhooks.slack;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * Represents a Slack webhook payload with block structure.
 *
 * @see <a href="https://api.slack.com/reference/block-kit/blocks">Slack Block Kit</a>
 */
@Builder(toBuilder = true)
public record SlackWebhookPayload(
        @NonNull @JsonProperty("blocks") List<SlackBlock> blocks) {
}
