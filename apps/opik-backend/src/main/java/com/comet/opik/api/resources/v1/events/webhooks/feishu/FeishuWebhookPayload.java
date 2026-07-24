package com.comet.opik.api.resources.v1.events.webhooks.feishu;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
public record FeishuWebhookPayload(
        @NonNull @JsonProperty("msg_type") String msgType,
        @NonNull @JsonProperty("card") FeishuCard card) {
}
