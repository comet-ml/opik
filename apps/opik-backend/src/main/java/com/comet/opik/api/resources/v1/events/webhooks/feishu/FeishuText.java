package com.comet.opik.api.resources.v1.events.webhooks.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record FeishuText(
        @NonNull @JsonProperty("tag") String tag,
        @NonNull @JsonProperty("content") String content) {

    public static FeishuText plainText(@NonNull String content) {
        return FeishuText.builder()
                .tag("plain_text")
                .content(content)
                .build();
    }

    public static FeishuText larkMd(@NonNull String content) {
        return FeishuText.builder()
                .tag("lark_md")
                .content(content)
                .build();
    }
}
