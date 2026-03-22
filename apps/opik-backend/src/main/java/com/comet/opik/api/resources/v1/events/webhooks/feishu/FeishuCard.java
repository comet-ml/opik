package com.comet.opik.api.resources.v1.events.webhooks.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

@Builder(toBuilder = true)
public record FeishuCard(
        @NonNull @JsonProperty("header") FeishuCardHeader header,
        @NonNull @JsonProperty("elements") List<FeishuCardElement> elements) {
}
