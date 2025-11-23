package com.comet.opik.api.resources.v1.events.webhooks.slack;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.webhooks.pagerduty.PagerDutyWebhookPayloadMapper;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@UtilityClass
public class AlertPayloadAdapter {

    private static final TypeReference<List<Prompt>> LIST_PROMPT_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<Prompt> PROMPT_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<PromptVersion> PROMPT_VERSION_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<List<Guardrail>> LIST_GUARDRAIL_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<List<Experiment>> EXPERIMENT_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<MetricsAlertPayload> METRICS_ALERT_PAYLOAD_TYPE_REFERENCE = new TypeReference<>() {
    };

    public static WebhookEvent<Map<String, Object>> prepareWebhookPayload(
            @NonNull WebhookEvent<Map<String, Object>> event) {

        return prepareWebhookJsonPayload(deserializeEventPayload(event));
    }

    public static WebhookEvent<Map<String, Object>> deserializeEventPayload(
            @NonNull WebhookEvent<Map<String, Object>> event) {
        Map<String, Object> payload = event.getPayload();
        @SuppressWarnings("unchecked")
        List<String> metadatas = (List<String>) payload.getOrDefault("metadata", List.of());

        var deserializeMetadata = metadatas.stream()
                .map(metadata -> JsonUtils.readValue(metadata, payloadTypePerEventType(event.getEventType())))
                .toList();

        Map<String, Object> updatedPayload = new HashMap<>(payload);
        updatedPayload.put("metadata", deserializeMetadata);

        return event.toBuilder()
                .payload(updatedPayload)
                .build();
    }

    public static WebhookEvent<Map<String, Object>> prepareWebhookJsonPayload(
            @NonNull WebhookEvent<Map<String, Object>> event) {

        return event.toBuilder()
                .jsonPayload(JsonUtils.writeValueAsString(webhookEventPayloadPerType(event)))
                .build();
    }

    public static Object webhookEventPayloadPerType(@NonNull WebhookEvent<Map<String, Object>> event) {
        return switch (event.getAlertType()) {
            case GENERAL ->
                event.toBuilder().url(null).headers(null).secret(null).alertType(null).jsonPayload(null)
                        .alertMetadata(null).build();
            case PAGERDUTY -> PagerDutyWebhookPayloadMapper.toPagerDutyPayload(event);
            case SLACK -> SlackWebhookPayloadMapper.toSlackPayload(event);
        };
    }

    private static TypeReference<?> payloadTypePerEventType(AlertEventType eventType) {
        return switch (eventType) {
            case PROMPT_DELETED -> LIST_PROMPT_TYPE_REFERENCE;
            case PROMPT_CREATED -> PROMPT_TYPE_REFERENCE;
            case PROMPT_COMMITTED -> PROMPT_VERSION_TYPE_REFERENCE;
            case TRACE_GUARDRAILS_TRIGGERED -> LIST_GUARDRAIL_TYPE_REFERENCE;
            case EXPERIMENT_FINISHED -> EXPERIMENT_TYPE_REFERENCE;
            case TRACE_COST, TRACE_LATENCY, TRACE_ERRORS, TRACE_FEEDBACK_SCORE,
                    TRACE_THREAD_FEEDBACK_SCORE ->
                METRICS_ALERT_PAYLOAD_TYPE_REFERENCE;
        };
    }
}
