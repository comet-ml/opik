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
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maps webhook events to Slack-specific payload format with block structure.
 */
@Slf4j
@UtilityClass
public class SlackWebhookPayloadMapper {

    private static final int SLACK_HEADER_BLOCK_LIMIT = 150;
    private static final int SLACK_TEXT_BLOCK_LIMIT = 3000;
    public static final String BASE_URL_METADATA_KEY = "base_url";
    private static final String DEFAULT_BASE_URL = "http://localhost:5173";

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

        // Add details section(s) with 3000 character limit handling
        blocks.addAll(createDetailsBlocks(event));

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

    private static List<SlackBlock> createDetailsBlocks(@NonNull WebhookEvent<Map<String, Object>> event) {
        List<?> metadata = (List<?>) event.getPayload().getOrDefault("metadata", List.of());

        var metadataUrl = event.getAlertMetadata().getOrDefault(BASE_URL_METADATA_KEY, DEFAULT_BASE_URL);
        metadataUrl = metadataUrl.endsWith("/") ? metadataUrl : metadataUrl + "/";

        String baseUrl = metadataUrl + event.getWorkspaceName();

        DetailsBuildResult result = switch (event.getEventType()) {
            case PROMPT_CREATED -> buildPromptCreatedDetails(metadata, baseUrl);
            case PROMPT_DELETED -> buildPromptDeletedDetails(metadata);
            case PROMPT_COMMITTED -> buildPromptCommittedDetails(metadata, baseUrl);
            case TRACE_ERRORS -> buildTraceErrorsDetails(metadata, baseUrl);
            case TRACE_FEEDBACK_SCORE -> buildTraceFeedbackScoreDetails(metadata, baseUrl);
            case TRACE_THREAD_FEEDBACK_SCORE -> buildTraceThreadFeedbackScoreDetails(metadata, baseUrl);
            case TRACE_GUARDRAILS_TRIGGERED -> buildGuardrailsTriggeredDetails(metadata, baseUrl);
        };

        var blocks = new ArrayList<SlackBlock>();
        blocks.add(SlackBlock.section(result.mainText));

        // Add fallback block if text was truncated
        if (result.fallbackText != null) {
            blocks.add(SlackBlock.section(result.fallbackText));
        }

        return blocks;
    }

    /**
     * Result of building details text with optional fallback.
     */
    private record DetailsBuildResult(String mainText, String fallbackText) {
        DetailsBuildResult(String mainText) {
            this(mainText, null);
        }
    }

    private static DetailsBuildResult buildPromptCreatedDetails(@NonNull List<?> metadata,
            @NonNull String baseUrl) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No prompts created");
        }

        List<String> promptLinks = metadata.stream()
                .map(item -> (Prompt) item)
                .map(prompt -> buildPromptLink(prompt.id(), baseUrl))
                .toList();

        String mainText = "*Prompts Created:*\n" + String.join("\n", promptLinks);
        String fallbackText = String.format("Overall %d Prompts created, you could check them here: %s",
                promptLinks.size(), baseUrl + "/prompts");

        return checkSlackTextLimit(mainText, "*Prompts Created:*\n", promptLinks,
                fallbackText);
    }

    private static DetailsBuildResult buildPromptDeletedDetails(@NonNull List<?> metadata) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No prompts deleted");
        }

        List<String> promptIds = metadata.stream()
                .map(item -> (List<Prompt>) item)
                .flatMap(List::stream)
                .map(prompt -> String.format("`%s`", prompt.id()))
                .toList();

        return new DetailsBuildResult("*Deleted Prompt IDs:*\n" + String.join(", ", promptIds));
    }

    private static DetailsBuildResult buildPromptCommittedDetails(@NonNull List<?> metadata,
            @NonNull String baseUrl) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No prompts committed");
        }

        List<String> commits = metadata.stream()
                .map(item -> (PromptVersion) item)
                .map(version -> buildPromptCommitLink(version.promptId(), version.id(), baseUrl))
                .toList();

        String mainText = "*Prompts Committed:*\n" + String.join("\n", commits);
        String fallbackText = String.format("Overall %d Prompts commits created, you could check them here: %s",
                commits.size(), baseUrl + "/prompts");

        return checkSlackTextLimit(mainText, "*Prompts Committed:*\n", commits, fallbackText);
    }

    private static DetailsBuildResult buildTraceErrorsDetails(@NonNull List<?> metadata,
            @NonNull String baseUrl) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No trace errors");
        }

        // Deduplicate traces with project IDs
        Set<Pair<UUID, UUID>> traceWithProjectIds = metadata.stream()
                .map(item -> (List<Trace>) item)
                .flatMap(List::stream)
                .map(trace -> Pair.of(trace.id(), trace.projectId()))
                .collect(Collectors.toSet());

        List<String> traceLinks = traceWithProjectIds.stream()
                .map(pair -> buildTraceLink(pair.getLeft(), pair.getRight(), baseUrl))
                .toList();

        String mainText = "*Traces with Errors:*\n" + String.join("\n", traceLinks);
        String fallbackText = String.format("Overall %d Traces with errors created, you could check them here: %s",
                traceLinks.size(), baseUrl + "/projects");

        return checkSlackTextLimit(mainText, "*Traces with Errors:*\n", traceLinks, fallbackText);
    }

    private static DetailsBuildResult buildTraceFeedbackScoreDetails(@NonNull List<?> metadata,
            @NonNull String baseUrl) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No feedback scores");
        }

        List<String> scoreLinks = metadata.stream()
                .map(item -> (List<FeedbackScoreItem.FeedbackScoreBatchItem>) item)
                .flatMap(List::stream)
                .map(fs -> buildTraceFeedbackScoreLink(fs, baseUrl))
                .toList();

        String mainText = "*Traces Feedback Scores:*\n" + String.join("\n", scoreLinks);
        String fallbackText = String.format("Overall %d Traces Feedback Scores created, you could check them here: %s",
                scoreLinks.size(), baseUrl + "/projects");

        return checkSlackTextLimit(mainText, "*Traces Feedback Scores:*\n", scoreLinks, fallbackText);
    }

    private static DetailsBuildResult buildTraceThreadFeedbackScoreDetails(@NonNull List<?> metadata,
            @NonNull String baseUrl) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No thread feedback scores");
        }

        List<String> scoreLinks = metadata.stream()
                .map(item -> (List<FeedbackScoreItem.FeedbackScoreBatchItemThread>) item)
                .flatMap(List::stream)
                .map(fs -> buildThreadFeedbackScoreLink(fs, baseUrl))
                .toList();

        String mainText = "*Threads Feedback Scores:*\n" + String.join("\n", scoreLinks);
        String fallbackText = String.format("Overall %d Threads Feedback Scores created, you could check them here: %s",
                scoreLinks.size(), baseUrl + "/projects");

        return checkSlackTextLimit(mainText, "*Threads Feedback Scores:*\n", scoreLinks, fallbackText);
    }

    private static DetailsBuildResult buildGuardrailsTriggeredDetails(@NonNull List<?> metadata,
            @NonNull String baseUrl) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No guardrails triggered");
        }

        // Deduplicate guardrails by entity ID (trace ID)
        Set<Pair<UUID, UUID>> traceWithProjectIds = metadata.stream()
                .map(item -> (List<Guardrail>) item)
                .flatMap(List::stream)
                .map(gr -> Pair.of(gr.entityId(), gr.projectId()))
                .collect(Collectors.toSet());

        List<String> guardrailLinks = traceWithProjectIds.stream()
                .map(pair -> buildTraceLink(pair.getLeft(), pair.getRight(), baseUrl))
                .toList();

        String mainText = "*Traces with Guardrails Triggered:*\n" + String.join("\n", guardrailLinks);
        String fallbackText = String.format(
                "Overall %d Traces with Guardrails Triggered created, you could check them here: %s",
                guardrailLinks.size(), baseUrl + "/projects");

        return checkSlackTextLimit(mainText, "*Traces with Guardrails Triggered:*\n", guardrailLinks, fallbackText);
    }

    private static DetailsBuildResult checkSlackTextLimit(String text, String mainText,
            List<String> links, String fallbackText) {
        // Check if exceeds limit
        if (text.length() > SLACK_TEXT_BLOCK_LIMIT) {
            // Truncate and create fallback
            List<String> includedLinks = new ArrayList<>();
            int currentLength = mainText.length();

            for (String link : links) {
                int lineLength = link.length() + 1; // +1 for newline
                if (currentLength + lineLength <= SLACK_TEXT_BLOCK_LIMIT - 50) {
                    includedLinks.add(link);
                    currentLength += lineLength;
                } else {
                    break;
                }
            }

            var updatedText = mainText + String.join("\n", includedLinks);

            return new DetailsBuildResult(updatedText, fallbackText);
        }

        return new DetailsBuildResult(text);
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

    /**
     * Builds a Slack-formatted link to a prompt in the UI.
     */
    private static String buildPromptLink(@NonNull UUID promptId, @NonNull String baseUrl) {
        String url = String.format("%s/prompts/%s", baseUrl, promptId);
        return String.format("<Prompt `%s`| %s>", promptId, url);
    }

    /**
     * Builds a Slack-formatted link to a prompt commit in the UI.
     */
    private static String buildPromptCommitLink(@NonNull UUID promptId, @NonNull UUID commitId,
            @NonNull String baseUrl) {
        String url = String.format("%s/prompts/%s?activeVersionId=%s", baseUrl, promptId, commitId);
        return String.format("<Prompt `%s`> (version `%s`) | %s", promptId, commitId, url);
    }

    /**
     * Builds a Slack-formatted link to a trace in the UI.
     */
    private static String buildTraceLink(@NonNull UUID traceId, @NonNull UUID projectId,
            @NonNull String baseUrl) {
        String url = String.format("%s/projects/%s/traces?trace=%s",
                baseUrl, projectId, traceId);
        return String.format("<Trace `%s` | %s>", traceId, url);
    }

    /**
     * Builds a Slack-formatted link to a trace feedback score in the UI.
     */
    private static String buildTraceFeedbackScoreLink(@NonNull FeedbackScoreItem.FeedbackScoreBatchItem fs,
            @NonNull String baseUrl) {

        String url = String.format("%s/projects/%s/traces?trace=%s&traceTab=feedback_scores",
                baseUrl, fs.projectId(), fs.id());
        return String.format("Trace Score  *%s* = %.2f, reason: %s | %s",
                fs.name(), fs.value(), fs.reason() != null ? fs.reason() : "N/A", url);
    }

    /**
     * Builds a Slack-formatted link to a thread feedback score in the UI.
     */
    private static String buildThreadFeedbackScoreLink(@NonNull FeedbackScoreItem.FeedbackScoreBatchItemThread fs,
            @NonNull String baseUrl) {
        String url = String.format("%s/projects/%s/traces?type=threads&thread=%s&threadTab=feedback_scores",
                baseUrl, fs.projectId(), fs.threadId());
        return String.format("Thread Score  *%s* = %.2f, reason: %s | %s",
                fs.name(), fs.value(), fs.reason() != null ? fs.reason() : "N/A", url);
    }
}
