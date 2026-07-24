package com.comet.opik.api.resources.v1.events.webhooks.feishu;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.webhooks.common.AlertWebhookUtils;
import com.comet.opik.utils.template.TemplateUtils;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maps webhook events to Feishu (Lark) Interactive Card payload format.
 * Shared formatting (event-type labels, window duration, base URL resolution) lives in
 * {@link AlertWebhookUtils} and is reused across webhook destinations.
 *
 * @see <a href="https://open.feishu.cn/document/common-capabilities/message-card/message-cards-content">Feishu Card</a>
 */
@Slf4j
@UtilityClass
public class FeishuWebhookPayloadMapper {

    private static final String METRICS_ALERT_DETAILS_TEMPLATE = "- **Current <type>:** <valuePrefix><metricValue><valueSuffix>\n"
            + "  **Threshold:** <valuePrefix><threshold><valueSuffix>\n"
            + "  **Time Window:** <windowDuration>\n"
            + "<if(feedbackScoreName)>  **Feedback Score:** `<feedbackScoreName>`\n<endif>"
            + "  **Scope:** <scope>";
    private static final String PROJECTS_TEMPLATE = "**Projects:** `<projectNames>`";

    public static FeishuWebhookPayload toFeishuPayload(@NonNull WebhookEvent<Map<String, Object>> event) {
        log.debug("Mapping webhook event to Feishu payload: eventType='{}'", event.getEventType());

        var elements = new ArrayList<FeishuCardElement>();

        // Add content div with summary + details
        String content = buildContent(event);
        elements.add(FeishuCardElement.div(FeishuText.larkMd(content)));

        // Add "View in Opik" button if applicable
        String actionUrl = buildActionUrl(event);
        if (actionUrl != null) {
            elements.add(FeishuCardElement.action(
                    List.of(FeishuAction.primaryButton("View in Opik", actionUrl))));
        }

        return FeishuWebhookPayload.builder()
                .msgType("interactive")
                .card(FeishuCard.builder()
                        .header(FeishuCardHeader.builder()
                                .title(FeishuText.plainText("Opik Alert: " + event.getAlertName()))
                                .template(determineTemplate(event.getEventType()))
                                .build())
                        .elements(elements)
                        .build())
                .build();
    }

    private static String buildContent(@NonNull WebhookEvent<Map<String, Object>> event) {
        List<?> metadata = (List<?>) event.getPayload().getOrDefault("metadata", List.of());
        int count = metadata.size();
        String eventTypeName = AlertWebhookUtils.formatEventType(event.getEventType());

        String summary = String.format("**%d** new %s event%s happened",
                count, eventTypeName, count != 1 ? "s" : "");

        String baseUrl = AlertWebhookUtils.resolveBaseUrl(event.getAlertMetadata(), event.getWorkspaceName());

        String details = switch (event.getEventType()) {
            case PROMPT_CREATED -> buildPromptCreatedDetails(metadata, baseUrl);
            case PROMPT_DELETED -> buildPromptDeletedDetails(metadata);
            case PROMPT_COMMITTED -> buildPromptCommittedDetails(metadata, baseUrl);
            case TRACE_ERRORS -> buildMetricsAlertDetails(metadata, "Trace Errors",
                    "No trace error alerts triggered");
            case TRACE_FEEDBACK_SCORE -> buildMetricsAlertDetails(metadata, "Trace Feedback Score",
                    "No trace feedback score alerts triggered");
            case TRACE_THREAD_FEEDBACK_SCORE -> buildMetricsAlertDetails(metadata, "Thread Feedback Score",
                    "No thread feedback score alerts triggered");
            case TRACE_GUARDRAILS_TRIGGERED -> buildGuardrailsTriggeredDetails(metadata, baseUrl);
            case EXPERIMENT_FINISHED -> buildExperimentFinishedDetails(metadata, baseUrl);
            case TRACE_COST -> buildMetricsAlertDetails(metadata, "Cost",
                    "No cost alerts triggered");
            case TRACE_LATENCY -> buildMetricsAlertDetails(metadata, "Latency",
                    "No latency alerts triggered");
        };

        return summary + "\n\n" + details;
    }

    private static String buildActionUrl(@NonNull WebhookEvent<Map<String, Object>> event) {
        String baseUrl = AlertWebhookUtils.resolveBaseUrl(event.getAlertMetadata(), event.getWorkspaceName());

        List<?> metadata = (List<?>) event.getPayload().getOrDefault("metadata", List.of());

        return switch (event.getEventType()) {
            case PROMPT_CREATED, PROMPT_COMMITTED, PROMPT_DELETED -> baseUrl + "/prompts";
            case TRACE_GUARDRAILS_TRIGGERED -> baseUrl + "/projects";
            case EXPERIMENT_FINISHED -> baseUrl + "/experiments";
            case TRACE_ERRORS, TRACE_FEEDBACK_SCORE, TRACE_THREAD_FEEDBACK_SCORE, TRACE_COST, TRACE_LATENCY -> {
                List<String> projectIds = metadata.stream()
                        .map(item -> ((MetricsAlertPayload) item).projectIds())
                        .filter(StringUtils::isNotBlank)
                        .flatMap(ids -> Arrays.stream(ids.split(",")))
                        .distinct()
                        .toList();
                String feTabType = event.getEventType() == AlertEventType.TRACE_THREAD_FEEDBACK_SCORE
                        ? "threads"
                        : "traces";
                if (projectIds.size() == 1) {
                    yield String.format("%s/projects/%s/traces?type=%s", baseUrl, projectIds.getFirst(), feTabType);
                } else {
                    yield baseUrl + "/projects";
                }
            }
        };
    }

    private static String buildPromptCreatedDetails(List<?> metadata, String baseUrl) {
        if (metadata.isEmpty()) {
            return "No prompts created";
        }

        String links = metadata.stream()
                .map(item -> (Prompt) item)
                .map(prompt -> String.format("- Prompt `%s` | [View](%s/prompts/%s)",
                        prompt.id(), baseUrl, prompt.id()))
                .collect(Collectors.joining("\n"));

        return "**Prompts Created:**\n" + links;
    }

    private static String buildPromptDeletedDetails(List<?> metadata) {
        if (metadata.isEmpty()) {
            return "No prompts deleted";
        }

        String ids = metadata.stream()
                .map(item -> (List<Prompt>) item)
                .flatMap(List::stream)
                .map(prompt -> String.format("`%s`", prompt.id()))
                .collect(Collectors.joining(", "));

        return "**Deleted Prompt IDs:** " + ids;
    }

    private static String buildPromptCommittedDetails(List<?> metadata, String baseUrl) {
        if (metadata.isEmpty()) {
            return "No prompts committed";
        }

        String links = metadata.stream()
                .map(item -> (PromptVersion) item)
                .map(version -> String.format("- Prompt `%s` (version `%s`) | [View](%s/prompts/%s?activeVersionId=%s)",
                        version.promptId(), version.id(), baseUrl, version.promptId(), version.id()))
                .collect(Collectors.joining("\n"));

        return "**Prompts Committed:**\n" + links;
    }

    private static String buildGuardrailsTriggeredDetails(List<?> metadata, String baseUrl) {
        if (metadata.isEmpty()) {
            return "No guardrails triggered";
        }

        Set<Pair<UUID, UUID>> traceWithProjectIds = metadata.stream()
                .map(item -> (List<Guardrail>) item)
                .flatMap(List::stream)
                .map(gr -> Pair.of(gr.entityId(), gr.projectId()))
                .collect(Collectors.toSet());

        String links = traceWithProjectIds.stream()
                .map(pair -> String.format("- Trace `%s` | [View](%s/projects/%s/traces?trace=%s)",
                        pair.getLeft(), baseUrl, pair.getRight(), pair.getLeft()))
                .collect(Collectors.joining("\n"));

        return "**Traces with Guardrails Triggered:**\n" + links;
    }

    private static String buildExperimentFinishedDetails(List<?> metadata, String baseUrl) {
        if (metadata.isEmpty()) {
            return "No experiments finished";
        }

        String links = metadata.stream()
                .map(item -> (List<Experiment>) item)
                .flatMap(List::stream)
                .map(experiment -> {
                    String experimentsParam = String.format("[\"%s\"]", experiment.id());
                    String encodedParam = URLEncoder.encode(experimentsParam, StandardCharsets.UTF_8);
                    return String.format("- Experiment `%s` | [View](%s/experiments/%s/compare?experiments=%s)",
                            experiment.id(), baseUrl, experiment.datasetId(), encodedParam);
                })
                .collect(Collectors.joining("\n"));

        return "**Experiments Finished:**\n" + links;
    }

    private static String buildMetricsAlertDetails(List<?> metadata, String metricType, String emptyMessage) {
        if (metadata.isEmpty()) {
            return emptyMessage;
        }

        String alertDetails = metadata.stream()
                .map(item -> formatMetricsAlertPayload((MetricsAlertPayload) item, metricType))
                .collect(Collectors.joining("\n"));

        return String.format("**%s Alert Triggered:**\n%s", metricType, alertDetails);
    }

    private static String formatMetricsAlertPayload(@NonNull MetricsAlertPayload payload, String type) {
        try {
            String windowDuration = AlertWebhookUtils.formatWindowDuration(payload.windowSeconds());

            String scope = (payload.projectIds() == null || payload.projectIds().isEmpty())
                    ? "*Workspace-wide*"
                    : TemplateUtils.newST(PROJECTS_TEMPLATE)
                            .add("projectNames", payload.projectNames())
                            .render();

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

            if (payload.feedbackScoreName() != null) {
                st.add("feedbackScoreName", payload.feedbackScoreName());
            }

            return st.render();
        } catch (Exception e) {
            // Defensive fallback (mirrors SlackWebhookPayloadMapper): a single malformed payload
            // must not abort the whole alert notification, so the broad catch is intentional here.
            log.error("Failed to format metrics alert payload: '{}'", payload, e);
            return "- %s alert (unable to parse details)".formatted(type);
        }
    }

    private static String determineTemplate(@NonNull AlertEventType eventType) {
        return switch (eventType) {
            case TRACE_ERRORS -> "red";
            case TRACE_COST, TRACE_LATENCY, TRACE_FEEDBACK_SCORE, TRACE_THREAD_FEEDBACK_SCORE,
                    TRACE_GUARDRAILS_TRIGGERED ->
                "orange";
            case PROMPT_CREATED, PROMPT_COMMITTED, PROMPT_DELETED, EXPERIMENT_FINISHED -> "blue";
        };
    }
}
