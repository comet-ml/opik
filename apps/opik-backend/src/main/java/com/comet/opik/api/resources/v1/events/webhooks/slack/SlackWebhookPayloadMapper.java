package com.comet.opik.api.resources.v1.events.webhooks.slack;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps webhook events to Slack-specific payload format with block structure.
 */
@Slf4j
@UtilityClass
public class SlackWebhookPayloadMapper {

    private static final int SLACK_HEADER_BLOCK_LIMIT = 150;

    /**
     * Converts a webhook event to Slack webhook payload.
     *
     * @param event the webhook event to convert
     * @return the Slack webhook payload
     */
    public static SlackWebhookPayload toSlackPayload(@NonNull WebhookEvent<Map<String, Object>> event) {
        log.debug("Mapping webhook event to Slack payload: eventType='{}'", event.getEventType());

        var blocks = new ArrayList<SlackBlock>();

        // Add header block with alert name
        blocks.add(createHeaderBlock(event));

        // Add summary section
        blocks.add(createSummaryBlock(event));

        // Add details section
        blocks.add(createDetailsBlock(event));

        return SlackWebhookPayload.builder()
                .blocks(blocks)
                .build();
    }

    private static SlackBlock createHeaderBlock(@NonNull WebhookEvent<Map<String, Object>> event) {
        return SlackBlock.header(
                event.getAlertName().substring(0, Math.min(event.getAlertName().length(), SLACK_HEADER_BLOCK_LIMIT)));
    }

    private static SlackBlock createSummaryBlock(@NonNull WebhookEvent<Map<String, Object>> event) {
        List<?> metadata = (List<?>) event.getPayload().getOrDefault("metadata", List.of());
        int count = metadata.size();
        String eventTypeName = formatEventType(event.getEventType());

        String summary = String.format("*%d* new %s event%s happened",
                count, eventTypeName, count != 1 ? "s" : "");

        return SlackBlock.section(summary);
    }

    private static SlackBlock createDetailsBlock(@NonNull WebhookEvent<Map<String, Object>> event) {
        String details = buildDetailsText(event);
        return SlackBlock.section(details);
    }

    private static String buildDetailsText(@NonNull WebhookEvent<Map<String, Object>> event) {
        List<?> metadata = (List<?>) event.getPayload().getOrDefault("metadata", List.of());

        return switch (event.getEventType()) {
            case PROMPT_CREATED -> buildPromptCreatedDetails(metadata);
            case PROMPT_DELETED -> buildPromptDeletedDetails(metadata);
            case PROMPT_COMMITTED -> buildPromptCommittedDetails(metadata);
            case TRACE_ERRORS -> buildTraceErrorsDetails(metadata);
            case TRACE_FEEDBACK_SCORE -> buildTraceFeedbackScoreDetails(metadata);
            case TRACE_THREAD_FEEDBACK_SCORE -> buildTraceThreadFeedbackScoreDetails(metadata);
            case TRACE_GUARDRAILS_TRIGGERED -> buildGuardrailsTriggeredDetails(metadata);
        };
    }

    private static String buildPromptCreatedDetails(@NonNull List<?> metadata) {
        if (metadata.isEmpty()) {
            return "No prompts created";
        }

        List<String> promptIds = metadata.stream()
                .map(item -> (Prompt) item)
                .map(prompt -> String.format("`%s`", prompt.id()))
                .toList();

        return "*Prompt IDs:*\n" + String.join(", ", promptIds);
    }

    private static String buildPromptDeletedDetails(@NonNull List<?> metadata) {
        if (metadata.isEmpty()) {
            return "No prompts deleted";
        }

        List<String> promptIds = metadata.stream()
                .map(item -> (List<Prompt>) item)
                .flatMap(List::stream)
                .map(prompt -> String.format("`%s`", prompt.id()))
                .toList();

        return "*Prompt IDs:*\n" + String.join(", ", promptIds);
    }

    private static String buildPromptCommittedDetails(@NonNull List<?> metadata) {
        if (metadata.isEmpty()) {
            return "No prompts committed";
        }

        List<String> commits = metadata.stream()
                .map(item -> (PromptVersion) item)
                .map(version -> String.format("Prompt `%s` committed with version `%s`",
                        version.promptId(), version.commit()))
                .toList();

        return String.join("\n", commits);
    }

    private static String buildTraceErrorsDetails(@NonNull List<?> metadata) {
        if (metadata.isEmpty()) {
            return "No trace errors";
        }

        List<String> traceIds = metadata.stream()
                .map(item -> (List<Trace>) item)
                .flatMap(List::stream)
                .map(trace -> String.format("`%s`", trace.id()))
                .toList();

        return "*Trace IDs:*\n" + String.join(", ", traceIds);
    }

    private static String buildTraceFeedbackScoreDetails(@NonNull List<?> metadata) {
        if (metadata.isEmpty()) {
            return "No feedback scores";
        }

        List<String> scores = metadata.stream()
                .map(item -> (List<FeedbackScoreItem.FeedbackScoreBatchItem>) item)
                .flatMap(List::stream)
                .map(score -> String.format("• Trace ID: `%s`\n  *%s* = %.2f",
                        score.id(), score.name(), score.value()))
                .toList();

        return String.join("\n", scores);
    }

    private static String buildTraceThreadFeedbackScoreDetails(@NonNull List<?> metadata) {
        if (metadata.isEmpty()) {
            return "No thread feedback scores";
        }

        List<String> scores = metadata.stream()
                .map(item -> (List<FeedbackScoreItem.FeedbackScoreBatchItemThread>) item)
                .flatMap(List::stream)
                .map(score -> String.format("• Thread ID: `%s`\n  *%s* = %.2f",
                        score.threadId(), score.name(), score.value()))
                .toList();

        return String.join("\n", scores);
    }

    private static String buildGuardrailsTriggeredDetails(@NonNull List<?> metadata) {
        if (metadata.isEmpty()) {
            return "No guardrails triggered";
        }

        List<String> traceIds = metadata.stream()
                .map(item -> (List<Guardrail>) item)
                .flatMap(List::stream)
                .map(guardrail -> String.format("`%s`", guardrail.entityId()))
                .toList();

        return "*Trace IDs:*\n" + String.join(", ", traceIds);
    }

    private static String formatEventType(@NonNull AlertEventType eventType) {
        return switch (eventType) {
            case PROMPT_CREATED -> "Prompt Created";
            case PROMPT_DELETED -> "Prompt Deleted";
            case PROMPT_COMMITTED -> "Prompt Committed";
            case TRACE_ERRORS -> "Trace Errors";
            case TRACE_FEEDBACK_SCORE -> "Trace Feedback Score";
            case TRACE_THREAD_FEEDBACK_SCORE -> "Thread Feedback Score";
            case TRACE_GUARDRAILS_TRIGGERED -> "Guardrail Triggered";
        };
    }
}
