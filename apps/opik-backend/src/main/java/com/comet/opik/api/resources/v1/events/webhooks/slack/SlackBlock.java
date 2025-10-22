package com.comet.opik.api.resources.v1.events.webhooks.slack;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

/**
 * Represents a Slack block element.
 *
 * @see <a href="https://api.slack.com/reference/block-kit/blocks">Slack Block Kit</a>
 */
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlackBlock(
        @NonNull @JsonProperty("type") String type,
        @JsonProperty("text") SlackText text) {

    /**
     * Creates a header block.
     *
     * @param text the header text
     * @return a header block
     */
    public static SlackBlock header(@NonNull String text) {
        return SlackBlock.builder()
                .type("header")
                .text(SlackText.plainText(text))
                .build();
    }

    /**
     * Creates a section block with markdown text.
     *
     * @param text the markdown text
     * @return a section block
     */
    public static SlackBlock section(@NonNull String text) {
        return SlackBlock.builder()
                .type("section")
                .text(SlackText.markdown(text))
                .build();
    }
}
