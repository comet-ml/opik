package com.comet.opik.api.resources.v1.events.webhooks.slack;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

/**
 * Represents a Slack text element.
 *
 * @see <a href="https://api.slack.com/reference/block-kit/composition-objects#text">Slack Text Object</a>
 */
@Builder(toBuilder = true)
public record SlackText(
        @NonNull @JsonProperty("type") String type,
        @NonNull @JsonProperty("text") String text) {

    /**
     * Creates a plain text element.
     *
     * @param text the text content
     * @return a plain text element
     */
    public static SlackText plainText(@NonNull String text) {
        return SlackText.builder()
                .type("plain_text")
                .text(text)
                .build();
    }

    /**
     * Creates a markdown text element.
     *
     * @param text the markdown text content
     * @return a markdown text element
     */
    public static SlackText markdown(@NonNull String text) {
        return SlackText.builder()
                .type("mrkdwn")
                .text(text)
                .build();
    }
}
