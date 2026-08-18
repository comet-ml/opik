package com.comet.opik.api.resources.v1.events.webhooks.feishu;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FeishuCardElement(
        @NonNull @JsonProperty("tag") String tag,
        @JsonProperty("text") FeishuText text,
        @JsonProperty("actions") List<FeishuAction> actions) {

    public static FeishuCardElement div(@NonNull FeishuText text) {
        return FeishuCardElement.builder()
                .tag("div")
                .text(text)
                .build();
    }

    public static FeishuCardElement action(@NonNull List<FeishuAction> actions) {
        return FeishuCardElement.builder()
                .tag("action")
                .actions(actions)
                .build();
    }
}
