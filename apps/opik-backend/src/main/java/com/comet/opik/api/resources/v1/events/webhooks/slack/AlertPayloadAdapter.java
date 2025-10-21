package com.comet.opik.api.resources.v1.events.webhooks.slack;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.webhooks.WebhookEvent;
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

    private static final TypeReference<List<Trace>> LIST_TRACE_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<List<FeedbackScoreItem.FeedbackScoreBatchItem>> LIST_TRACE_SCORE_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<List<FeedbackScoreItem.FeedbackScoreBatchItemThread>> LIST_THREAD_SCORE_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<List<Guardrail>> LIST_GUARDRAIL_TYPE_REFERENCE = new TypeReference<>() {
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
            case GENERAL, PAGERDUTY ->
                event.toBuilder().url(null).headers(null).secret(null).alertType(null).jsonPayload(null).build();
            case SLACK -> SlackWebhookPayloadMapper.toSlackPayload(event);
        };
    }

    private static TypeReference<?> payloadTypePerEventType(AlertEventType eventType) {
        return switch (eventType) {
            case PROMPT_DELETED -> LIST_PROMPT_TYPE_REFERENCE;
            case PROMPT_CREATED -> PROMPT_TYPE_REFERENCE;
            case PROMPT_COMMITTED -> PROMPT_VERSION_TYPE_REFERENCE;
            case TRACE_ERRORS -> LIST_TRACE_TYPE_REFERENCE;
            case TRACE_FEEDBACK_SCORE -> LIST_TRACE_SCORE_TYPE_REFERENCE;
            case TRACE_THREAD_FEEDBACK_SCORE -> LIST_THREAD_SCORE_TYPE_REFERENCE;
            case TRACE_GUARDRAILS_TRIGGERED -> LIST_GUARDRAIL_TYPE_REFERENCE;
        };
    }
}
