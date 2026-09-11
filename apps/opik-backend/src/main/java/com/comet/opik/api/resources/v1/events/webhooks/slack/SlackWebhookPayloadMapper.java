package com.comet.opik.api.resources.v1.events.webhooks.slack;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.utils.template.TemplateUtils;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final String METRICS_ALERT_DETAILS_TEMPLATE = "• *Current <type>:* <valuePrefix><metricValue><valueSuffix>\n"
            +
            "  *Threshold:* <valuePrefix><threshold><valueSuffix>\n" +
            "  *Time Window:* <windowDuration>\n" +
            "<if(feedbackScoreName)>  *Feedback Score:* `<feedbackScoreName>`\n<endif>" +
            "  *Scope:* <scope>";
    private static final String PROJECTS_TEMPLATE = "*Projects:* `<projectNames>`";

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
            case TRACE_ERRORS -> buildTraceErrorsMetricsDetails(metadata, baseUrl);
            case TRACE_FEEDBACK_SCORE -> buildTraceFeedbackScoreDetails(metadata, baseUrl);
            case TRACE_THREAD_FEEDBACK_SCORE -> buildTraceThreadFeedbackScoreDetails(metadata, baseUrl);
            case TRACE_GUARDRAILS_TRIGGERED -> buildGuardrailsTriggeredDetails(metadata, baseUrl);
            case EXPERIMENT_FINISHED -> buildExperimentFinishedDetails(metadata, baseUrl);
            case TRACE_COST -> buildCostDetails(metadata, baseUrl);
            case TRACE_LATENCY -> buildLatencyDetails(metadata, baseUrl);
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

    private static DetailsBuildResult buildPromptCreatedDetails(List<?> metadata,
            String baseUrl) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No prompts created");
        }

        List<String> promptLinks = metadata.stream()
                .map(item -> (Prompt) item)
                .map(prompt -> buildPromptLink(prompt.id(), baseUrl))
                .toList();

        String mainText = "*Prompts Created:*\n" + String.join("\n", promptLinks);
        String fallbackText = String.format("Overall %d Prompts created, you could check them here: <%s|View All>",
                promptLinks.size(), baseUrl + "/prompts");

        return checkSlackTextLimit(mainText, "*Prompts Created:*\n", promptLinks,
                fallbackText);
    }

    private static DetailsBuildResult buildPromptDeletedDetails(List<?> metadata) {
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

    private static DetailsBuildResult buildPromptCommittedDetails(List<?> metadata,
            String baseUrl) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No prompts committed");
        }

        List<String> commits = metadata.stream()
                .map(item -> (PromptVersion) item)
                .map(version -> buildPromptCommitLink(version.promptId(), version.id(), baseUrl))
                .toList();

        String mainText = "*Prompts Committed:*\n" + String.join("\n", commits);
        String fallbackText = String.format(
                "Overall %d Prompts commits created, you could check them here: <%s|View All>",
                commits.size(), baseUrl + "/prompts");

        return checkSlackTextLimit(mainText, "*Prompts Committed:*\n", commits, fallbackText);
    }

    private static DetailsBuildResult buildTraceFeedbackScoreDetails(List<?> metadata,
            String baseUrl) {
        return buildMetricsAlertDetails(metadata, baseUrl, "Trace Feedback Score",
                "No trace feedback score alerts triggered");
    }

    private static DetailsBuildResult buildTraceThreadFeedbackScoreDetails(List<?> metadata,
            String baseUrl) {
        return buildMetricsAlertDetails(metadata, baseUrl, "Thread Feedback Score",
                "No thread feedback score alerts triggered", "threads");
    }

    private static DetailsBuildResult buildGuardrailsTriggeredDetails(List<?> metadata,
            String baseUrl) {
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
                "Overall %d Traces with Guardrails Triggered created, you could check them here: <%s|View All>",
                guardrailLinks.size(), baseUrl + "/projects");

        return checkSlackTextLimit(mainText, "*Traces with Guardrails Triggered:*\n", guardrailLinks, fallbackText);
    }

    private static DetailsBuildResult buildExperimentFinishedDetails(List<?> metadata,
            String baseUrl) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult("No experiments finished");
        }

        List<String> experimentLinks = metadata.stream()
                .map(item -> (List<Experiment>) item)
                .flatMap(List::stream)
                .map(experiment -> buildExperimentLink(experiment.id(), experiment.datasetId(), baseUrl))
                .toList();

        String mainText = "*Experiments Finished:*\n" + String.join("\n", experimentLinks);
        String fallbackText = String.format(
                "Overall %d Experiments finished, you could check them here: <%s|View All>",
                experimentLinks.size(), baseUrl + "/experiments");

        return checkSlackTextLimit(mainText, "*Experiments Finished:*\n", experimentLinks, fallbackText);
    }

    private static DetailsBuildResult buildTraceErrorsMetricsDetails(List<?> metadata,
            String baseUrl) {
        return buildMetricsAlertDetails(metadata, baseUrl, "Trace Errors",
                "No trace error alerts triggered");
    }

    private static DetailsBuildResult buildCostDetails(List<?> metadata,
            String baseUrl) {
        return buildMetricsAlertDetails(metadata, baseUrl, "Cost",
                "No cost alerts triggered");
    }

    private static DetailsBuildResult buildLatencyDetails(List<?> metadata,
            String baseUrl) {
        return buildMetricsAlertDetails(metadata, baseUrl, "Latency",
                "No latency alerts triggered");
    }

    private static DetailsBuildResult buildMetricsAlertDetails(List<?> metadata,
            String baseUrl, String metricType, String emptyMessage) {
        return buildMetricsAlertDetails(metadata, baseUrl, metricType, emptyMessage, "traces");
    }

    /**
     * Builds metrics alert details for Slack notification.
     *
     * @param metadata the alert metadata containing MetricsAlertPayload objects
     * @param baseUrl the base URL for creating links
     * @param metricType the type of metric (e.g., "Cost", "Latency", "Trace Errors")
     * @param emptyMessage the message to display when no alerts are triggered
     * @return the formatted details result
     */
    private static DetailsBuildResult buildMetricsAlertDetails(List<?> metadata,
            String baseUrl, String metricType, String emptyMessage, String feTabType) {
        if (metadata.isEmpty()) {
            return new DetailsBuildResult(emptyMessage);
        }

        List<String> alertDetails = metadata.stream()
                .map(item -> formatMetricsAlertPayload((MetricsAlertPayload) item, metricType))
                .toList();

        List<String> projectIds = metadata.stream()
                .map(item -> ((MetricsAlertPayload) item).projectIds())
                .filter(StringUtils::isNotBlank)
                .flatMap(ids -> Arrays.stream(ids.split(",")))
                .distinct()
                .toList();

        String mainText = String.format("*%s Alert Triggered:*\n%s", metricType, String.join("\n", alertDetails));
        String projectsLink = projectIds.size() == 1
                ? String.format("\n\n<%s/projects/%s/traces?type=%s|View Project>", baseUrl, projectIds.getFirst(),
                        feTabType)
                : String.format("\n\n<%s/projects|View All Projects>", baseUrl);

        return new DetailsBuildResult(mainText + projectsLink);
    }

    /**
     * Formats metrics alert payload into a readable Slack message.
     */
    private static String formatMetricsAlertPayload(@NonNull MetricsAlertPayload payload, String type) {
        try {
            // Format window duration
            String windowDuration = formatWindowDuration(payload.windowSeconds());

            // Build scope description
            String scope = (payload.projectIds() == null || payload.projectIds().isEmpty())
                    ? "*Workspace-wide*"
                    : TemplateUtils.newST(PROJECTS_TEMPLATE)
                            .add("projectNames", payload.projectNames())
                            .render();

            // Format based on metric type
            String valuePrefix = type.equals("Cost") ? "$" : "";
            String valueSuffix = type.equals("Latency") ? " s" : "";

            var st = TemplateUtils.newST(METRICS_ALERT_DETAILS_TEMPLATE);
            st.add("type", type);
            st.add("valuePrefix", valuePrefix);
            st.add("metricValue", payload.metricValue());
            st.add("valueSuffix", valueSuffix);
            st.add("threshold", payload.threshold());
            st.add("windowDuration", windowDuration);
            st.add("scope", scope);

            // Only add feedback score name if it's not null
            if (payload.feedbackScoreName() != null) {
                st.add("feedbackScoreName", payload.feedbackScoreName());
            }

            return st.render();
        } catch (Exception e) {
            log.error("Failed to format metrics alert payload: '{}'", payload, e);
            return "• %s alert (unable to parse details)".formatted(type);
        }
    }

    /**
     * Formats window duration from seconds to human-readable format.
     */
    private static String formatWindowDuration(long seconds) {
        Duration duration = Duration.ofSeconds(seconds);

        if (duration.toDays() > 0) {
            long days = duration.toDays();
            return days + " day" + (days != 1 ? "s" : "");
        } else if (duration.toHours() > 0) {
            long hours = duration.toHours();
            return hours + " hour" + (hours != 1 ? "s" : "");
        } else if (duration.toMinutes() > 0) {
            long minutes = duration.toMinutes();
            return minutes + " minute" + (minutes != 1 ? "s" : "");
        } else {
            return seconds + " second" + (seconds != 1 ? "s" : "");
        }
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
            case TRACE_ERRORS -> "Trace Error Alert";
            case TRACE_FEEDBACK_SCORE -> "Trace Feedback Score";
            case TRACE_THREAD_FEEDBACK_SCORE -> "Thread Feedback Score";
            case TRACE_GUARDRAILS_TRIGGERED -> "Guardrail Triggered";
            case EXPERIMENT_FINISHED -> "Experiment Finished";
            case TRACE_COST -> "Cost Alert";
            case TRACE_LATENCY -> "Latency Alert";
        };
    }

    /**
     * Builds a Slack-formatted link to a prompt in the UI.
     */
    private static String buildPromptLink(@NonNull UUID promptId, @NonNull String baseUrl) {
        String url = String.format("%s/prompts/%s", baseUrl, promptId);
        return String.format("Prompt `%s` | <%s|View>", promptId, url);
    }

    /**
     * Builds a Slack-formatted link to a prompt commit in the UI.
     */
    private static String buildPromptCommitLink(@NonNull UUID promptId, @NonNull UUID commitId,
            @NonNull String baseUrl) {
        String url = String.format("%s/prompts/%s?activeVersionId=%s", baseUrl, promptId, commitId);
        return String.format("Prompt `%s` (version `%s`) | <%s|View>", promptId, commitId, url);
    }

    /**
     * Builds a Slack-formatted link to a trace in the UI.
     */
    private static String buildTraceLink(@NonNull UUID traceId, @NonNull UUID projectId,
            @NonNull String baseUrl) {
        String url = String.format("%s/projects/%s/traces?trace=%s",
                baseUrl, projectId, traceId);
        return String.format("Trace `%s` | <%s|View>", traceId, url);
    }

    /**
     * Builds a Slack-formatted link to an experiment in the UI.
     */
    private static String buildExperimentLink(@NonNull UUID experimentId, @NonNull UUID datasetId,
            @NonNull String baseUrl) {
        String experimentsParam = String.format("[\"%s\"]", experimentId);
        String encodedExperimentsParam = URLEncoder.encode(experimentsParam, StandardCharsets.UTF_8);
        String url = String.format("%s/experiments/%s/compare?experiments=%s",
                baseUrl, datasetId, encodedExperimentsParam);
        return String.format("Experiment `%s` | <%s|View>", experimentId, url);
    }
}
