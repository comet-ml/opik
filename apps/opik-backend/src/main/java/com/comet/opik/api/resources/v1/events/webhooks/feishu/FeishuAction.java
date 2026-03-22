package com.comet.opik.api.resources.v1.events.webhooks.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record FeishuAction(
        @NonNull @JsonProperty("tag") String tag,
        @NonNull @JsonProperty("text") FeishuText text,
        @NonNull @JsonProperty("url") String url,
        @NonNull @JsonProperty("type") String type) {

    public static FeishuAction primaryButton(@NonNull String label, @NonNull String url) {
        return FeishuAction.builder()
                .tag("button")
                .text(FeishuText.plainText(label))
                .url(url)
                .type("primary")
                .build();
    }
}
