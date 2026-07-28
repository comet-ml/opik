package com.comet.opik.api.resources.v1.events.webhooks.feishu;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.v1.events.webhooks.common.AlertWebhookUtils;
import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeishuWebhookPayloadMapperTest {

    private static final String ALERT_NAME = "Test Feishu Alert";
    private static final String BASE_URL = "http://localhost:5555";
    private static final String WORKSPACE_NAME = "default";

    private WebhookEvent<Map<String, Object>> buildEvent(
            AlertEventType eventType, List<?> metadata) {
        return buildEvent(eventType, metadata, Map.of(AlertWebhookUtils.BASE_URL_METADATA_KEY, BASE_URL));
    }

    private WebhookEvent<Map<String, Object>> buildEvent(
            AlertEventType eventType, List<?> metadata, Map<String, String> alertMetadata) {
        return WebhookEvent.<Map<String, Object>>builder()
                .id("event-" + System.currentTimeMillis())
                .eventType(eventType)
                .alertType(AlertType.FEISHU)
                .alertId(UUID.randomUUID())
                .alertName(ALERT_NAME)
                .alertMetadata(alertMetadata)
                .workspaceId("workspace-1")
                .workspaceName(WORKSPACE_NAME)
                .userName("test-user")
                .payload(Map.of("metadata", metadata))
                .createdAt(Instant.now())
                .url("https://open.feishu.cn/open-apis/bot/v2/hook/test")
                .headers(Map.of())
                .build();
    }

    @Nested
    class CardStructure {

        @Test
        void shouldBuildInteractiveMsgType() {
            var event = buildEvent(AlertEventType.PROMPT_CREATED,
                    List.of(Prompt.builder().id(UUID.randomUUID()).name("p1").build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            assertThat(payload.msgType()).isEqualTo("interactive");
            assertThat(payload.card()).isNotNull();
            assertThat(payload.card().header()).isNotNull();
            assertThat(payload.card().header().title().tag()).isEqualTo("plain_text");
            assertThat(payload.card().header().title().content()).isEqualTo("Opik Alert: " + ALERT_NAME);
        }

        @Test
        void shouldContainDivAndActionElements() {
            var event = buildEvent(AlertEventType.PROMPT_CREATED,
                    List.of(Prompt.builder().id(UUID.randomUUID()).name("p1").build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            assertThat(payload.card().elements()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(payload.card().elements().get(0).tag()).isEqualTo("div");
            assertThat(payload.card().elements().get(0).text().tag()).isEqualTo("lark_md");

            // Last element should be action with button
            var lastElement = payload.card().elements().get(payload.card().elements().size() - 1);
            assertThat(lastElement.tag()).isEqualTo("action");
            assertThat(lastElement.actions()).isNotEmpty();
            assertThat(lastElement.actions().get(0).tag()).isEqualTo("button");
            assertThat(lastElement.actions().get(0).type()).isEqualTo("primary");
            assertThat(lastElement.actions().get(0).text().content()).isEqualTo("View in Opik");
        }
    }

    @Nested
    class TemplateColors {

        static Stream<Arguments> colorProvider() {
            return Stream.of(
                    Arguments.of(AlertEventType.TRACE_ERRORS, "red"),
                    Arguments.of(AlertEventType.TRACE_COST, "orange"),
                    Arguments.of(AlertEventType.TRACE_LATENCY, "orange"),
                    Arguments.of(AlertEventType.TRACE_FEEDBACK_SCORE, "orange"),
                    Arguments.of(AlertEventType.TRACE_THREAD_FEEDBACK_SCORE, "orange"),
                    Arguments.of(AlertEventType.TRACE_GUARDRAILS_TRIGGERED, "orange"),
                    Arguments.of(AlertEventType.PROMPT_CREATED, "blue"),
                    Arguments.of(AlertEventType.PROMPT_COMMITTED, "blue"),
                    Arguments.of(AlertEventType.PROMPT_DELETED, "blue"),
                    Arguments.of(AlertEventType.EXPERIMENT_FINISHED, "blue"));
        }

        @ParameterizedTest
        @MethodSource("colorProvider")
        void shouldUseCorrectTemplateColor(AlertEventType eventType, String expectedColor) {
            List<?> metadata = buildMetadataForEventType(eventType);
            var event = buildEvent(eventType, metadata);

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            assertThat(payload.card().header().template()).isEqualTo(expectedColor);
        }
    }

    @Nested
    class EventTypeDetails {

        @Test
        void promptCreated() {
            UUID promptId = UUID.randomUUID();
            var event = buildEvent(AlertEventType.PROMPT_CREATED,
                    List.of(Prompt.builder().id(promptId).name("test-prompt").build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Prompt Created event happened

                    **Prompts Created:**
                    - Prompt `%s` | [View](%s/%s/prompts/%s)"""
                    .formatted(promptId, BASE_URL, WORKSPACE_NAME, promptId));
        }

        @Test
        void promptDeleted() {
            UUID promptId = UUID.randomUUID();
            var event = buildEvent(AlertEventType.PROMPT_DELETED,
                    List.of(List.of(Prompt.builder().id(promptId).name("deleted-prompt").build())));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Prompt Deleted event happened

                    **Deleted Prompt IDs:** `%s`""".formatted(promptId));
        }

        @Test
        void promptCommitted() {
            UUID promptId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            var event = buildEvent(AlertEventType.PROMPT_COMMITTED,
                    List.of(PromptVersion.builder().id(versionId).promptId(promptId).build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Prompt Committed event happened

                    **Prompts Committed:**
                    - Prompt `%s` (version `%s`) | [View](%s/%s/prompts/%s?activeVersionId=%s)"""
                    .formatted(promptId, versionId, BASE_URL, WORKSPACE_NAME, promptId, versionId));
        }

        @Test
        void traceErrors() {
            var event = buildEvent(AlertEventType.TRACE_ERRORS,
                    List.of(MetricsAlertPayload.builder()
                            .metricValue("15")
                            .threshold("10")
                            .windowSeconds(3600)
                            .build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Trace Error Alert event happened

                    **Trace Errors Alert Triggered:**
                    - **Current Trace Errors:** 15
                      **Threshold:** 10
                      **Time Window:** 1 hour
                      **Scope:** **Workspace-wide**""");
        }

        @Test
        void traceFeedbackScore() {
            var event = buildEvent(AlertEventType.TRACE_FEEDBACK_SCORE,
                    List.of(MetricsAlertPayload.builder()
                            .metricValue("0.85")
                            .threshold("0.9")
                            .windowSeconds(86400)
                            .feedbackScoreName("accuracy")
                            .projectIds("proj-1")
                            .projectNames("My Project")
                            .build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Trace Feedback Score event happened

                    **Trace Feedback Score Alert Triggered:**
                    - **Current Trace Feedback Score:** 0.85
                      **Threshold:** 0.9
                      **Time Window:** 1 day
                      **Feedback Score:** `accuracy`
                      **Scope:** **Projects:** `My Project`""");
        }

        @Test
        void traceThreadFeedbackScore() {
            var event = buildEvent(AlertEventType.TRACE_THREAD_FEEDBACK_SCORE,
                    List.of(MetricsAlertPayload.builder()
                            .metricValue("0.7")
                            .threshold("0.8")
                            .windowSeconds(7200)
                            .feedbackScoreName("relevance")
                            .build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Thread Feedback Score event happened

                    **Thread Feedback Score Alert Triggered:**
                    - **Current Thread Feedback Score:** 0.7
                      **Threshold:** 0.8
                      **Time Window:** 2 hours
                      **Feedback Score:** `relevance`
                      **Scope:** **Workspace-wide**""");
        }

        @Test
        void traceCost() {
            var event = buildEvent(AlertEventType.TRACE_COST,
                    List.of(MetricsAlertPayload.builder()
                            .metricValue("150.50")
                            .threshold("100.00")
                            .windowSeconds(3600)
                            .build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Cost Alert event happened

                    **Cost Alert Triggered:**
                    - **Current Cost:** $150.50
                      **Threshold:** $100.00
                      **Time Window:** 1 hour
                      **Scope:** **Workspace-wide**""");
        }

        @Test
        void traceLatency() {
            var event = buildEvent(AlertEventType.TRACE_LATENCY,
                    List.of(MetricsAlertPayload.builder()
                            .metricValue("5.2")
                            .threshold("3.0")
                            .windowSeconds(1800)
                            .build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Latency Alert event happened

                    **Latency Alert Triggered:**
                    - **Current Latency:** 5.2 s
                      **Threshold:** 3.0 s
                      **Time Window:** 30 minutes
                      **Scope:** **Workspace-wide**""");
        }

        @Test
        void guardrailsTriggered() {
            UUID traceId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            var event = buildEvent(AlertEventType.TRACE_GUARDRAILS_TRIGGERED,
                    List.of(List.of(Guardrail.builder()
                            .entityId(traceId)
                            .projectId(projectId)
                            .secondaryId(UUID.randomUUID())
                            .build())));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Guardrail Triggered event happened

                    **Traces with Guardrails Triggered:**
                    - Trace `%s` | [View](%s/%s/projects/%s/traces?trace=%s)"""
                    .formatted(traceId, BASE_URL, WORKSPACE_NAME, projectId, traceId));
        }

        @Test
        void experimentFinished() {
            UUID experimentId = UUID.randomUUID();
            UUID datasetId = UUID.randomUUID();
            var event = buildEvent(AlertEventType.EXPERIMENT_FINISHED,
                    List.of(List.of(Experiment.builder()
                            .id(experimentId)
                            .datasetId(datasetId)
                            .build())));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Experiment Finished event happened

                    **Experiments Finished:**
                    - Experiment `%s` | [View](%s/%s/experiments/%s/compare?experiments=%%5B%%22%s%%22%%5D)"""
                    .formatted(experimentId, BASE_URL, WORKSPACE_NAME, datasetId, experimentId));
        }

        @Test
        void rendersMultipleEventsAndPluralizesSummary() {
            UUID firstPromptId = UUID.randomUUID();
            UUID secondPromptId = UUID.randomUUID();
            var event = buildEvent(AlertEventType.PROMPT_CREATED, List.of(
                    Prompt.builder().id(firstPromptId).name("first").build(),
                    Prompt.builder().id(secondPromptId).name("second").build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            assertThat(payload.card().elements().get(0).text().content()).isEqualTo("""
                    **2** new Prompt Created events happened

                    **Prompts Created:**
                    - Prompt `%s` | [View](%s/%s/prompts/%s)
                    - Prompt `%s` | [View](%s/%s/prompts/%s)"""
                    .formatted(
                            firstPromptId, BASE_URL, WORKSPACE_NAME, firstPromptId,
                            secondPromptId, BASE_URL, WORKSPACE_NAME, secondPromptId));
        }
    }

    @Nested
    class DefaultBaseUrl {

        @Test
        void shouldUseDefaultBaseUrlWhenMetadataEmpty() {
            UUID promptId = UUID.randomUUID();
            var event = buildEvent(
                    AlertEventType.PROMPT_CREATED,
                    List.of(Prompt.builder().id(promptId).name("p1").build()),
                    Map.of());

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("""
                    **1** new Prompt Created event happened

                    **Prompts Created:**
                    - Prompt `%s` | [View](http://localhost:5173/%s/prompts/%s)"""
                    .formatted(promptId, WORKSPACE_NAME, promptId));
        }
    }

    @Nested
    class ActionUrls {

        @Test
        void metricsAlertWithSingleProjectShouldLinkToProject() {
            String projectId = UUID.randomUUID().toString();
            var event = buildEvent(AlertEventType.TRACE_ERRORS,
                    List.of(MetricsAlertPayload.builder()
                            .metricValue("15")
                            .threshold("10")
                            .windowSeconds(3600)
                            .projectIds(projectId)
                            .projectNames("My Project")
                            .build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            var actionElement = payload.card().elements().get(payload.card().elements().size() - 1);
            assertThat(actionElement.tag()).isEqualTo("action");
            assertThat(actionElement.actions().get(0).url())
                    .isEqualTo(BASE_URL + "/" + WORKSPACE_NAME + "/projects/" + projectId + "/traces?type=traces");
        }

        @Test
        void metricsAlertWithMultipleProjectsShouldLinkToProjectsList() {
            var event = buildEvent(AlertEventType.TRACE_COST,
                    List.of(MetricsAlertPayload.builder()
                            .metricValue("100")
                            .threshold("50")
                            .windowSeconds(3600)
                            .projectIds("proj-1,proj-2")
                            .projectNames("Project A,Project B")
                            .build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            var actionElement = payload.card().elements().get(payload.card().elements().size() - 1);
            assertThat(actionElement.actions().get(0).url())
                    .isEqualTo(BASE_URL + "/" + WORKSPACE_NAME + "/projects");
        }

        @Test
        void threadFeedbackScoreShouldUsethreadsTabType() {
            String projectId = UUID.randomUUID().toString();
            var event = buildEvent(AlertEventType.TRACE_THREAD_FEEDBACK_SCORE,
                    List.of(MetricsAlertPayload.builder()
                            .metricValue("0.7")
                            .threshold("0.8")
                            .windowSeconds(3600)
                            .projectIds(projectId)
                            .projectNames("My Project")
                            .build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            var actionElement = payload.card().elements().get(payload.card().elements().size() - 1);
            assertThat(actionElement.actions().get(0).url())
                    .isEqualTo(BASE_URL + "/" + WORKSPACE_NAME + "/projects/" + projectId + "/traces?type=threads");
        }
    }

    @Nested
    class DefensiveFormatting {

        @Test
        void fallsBackWhenMetricsPayloadIsMalformed() {
            MetricsAlertPayload malformedPayload = mock(MetricsAlertPayload.class);
            when(malformedPayload.windowSeconds()).thenThrow(new IllegalStateException("malformed"));
            var event = buildEvent(AlertEventType.TRACE_ERRORS, List.of(malformedPayload));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            assertThat(payload.card().elements().get(0).text().content()).isEqualTo("""
                    **1** new Trace Error Alert event happened

                    **Trace Errors Alert Triggered:**
                    - Trace Errors alert (unable to parse details)""");
        }

        static Stream<Arguments> oversizedContentProvider() {
            return Stream.of(
                    Arguments.of("JSON-escaped content", "\"\\\n".repeat(10_000)),
                    Arguments.of("ASCII boundary content", "a".repeat(30_000)),
                    Arguments.of("supplementary Unicode content", "😀".repeat(10_000)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("oversizedContentProvider")
        void keepsSerializedPayloadWithinFeishuCardLimit(String caseName, String metricValue) {
            var event = buildEvent(AlertEventType.TRACE_ERRORS, List.of(MetricsAlertPayload.builder()
                    .metricValue(metricValue)
                    .threshold("10")
                    .windowSeconds(3_600)
                    .build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);
            String content = payload.card().elements().get(0).text().content();
            byte[] serializedPayload = JsonUtils.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);

            assertThat(serializedPayload).hasSizeLessThanOrEqualTo(30_000);
            assertThat(StandardCharsets.UTF_8.newEncoder().canEncode(content)).isTrue();
            assertThat(content).endsWith("\n\n_Content truncated. Use \"View in Opik\" to see all events._");
        }
    }

    @Nested
    class EmptyMetadata {

        static Stream<Arguments> eventTypeProvider() {
            return Stream.of(
                    Arguments.of(AlertEventType.PROMPT_CREATED, "No prompts created"),
                    Arguments.of(AlertEventType.PROMPT_DELETED, "No prompts deleted"),
                    Arguments.of(AlertEventType.PROMPT_COMMITTED, "No prompts committed"),
                    Arguments.of(AlertEventType.TRACE_ERRORS, "No trace error alerts triggered"),
                    Arguments.of(AlertEventType.TRACE_FEEDBACK_SCORE, "No trace feedback score alerts triggered"),
                    Arguments.of(AlertEventType.TRACE_THREAD_FEEDBACK_SCORE,
                            "No thread feedback score alerts triggered"),
                    Arguments.of(AlertEventType.TRACE_GUARDRAILS_TRIGGERED, "No guardrails triggered"),
                    Arguments.of(AlertEventType.EXPERIMENT_FINISHED, "No experiments finished"),
                    Arguments.of(AlertEventType.TRACE_COST, "No cost alerts triggered"),
                    Arguments.of(AlertEventType.TRACE_LATENCY, "No latency alerts triggered"));
        }

        @ParameterizedTest
        @MethodSource("eventTypeProvider")
        void shouldHandleEmptyMetadata(AlertEventType eventType, String expectedMessage) {
            var event = buildEvent(eventType, List.of());

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).isEqualTo("**0** new " + AlertWebhookUtils.formatEventType(eventType)
                    + " events happened\n\n" + expectedMessage);
        }
    }

    private List<?> buildMetadataForEventType(AlertEventType eventType) {
        return switch (eventType) {
            case PROMPT_CREATED -> List.of(Prompt.builder().id(UUID.randomUUID()).name("p").build());
            case PROMPT_DELETED -> List.of(
                    List.of(Prompt.builder().id(UUID.randomUUID()).name("p").build()));
            case PROMPT_COMMITTED -> List.of(
                    PromptVersion.builder().id(UUID.randomUUID()).promptId(UUID.randomUUID()).build());
            case TRACE_GUARDRAILS_TRIGGERED -> List.of(
                    List.of(Guardrail.builder().entityId(UUID.randomUUID())
                            .projectId(UUID.randomUUID()).secondaryId(UUID.randomUUID()).build()));
            case EXPERIMENT_FINISHED -> List.of(
                    List.of(Experiment.builder().id(UUID.randomUUID()).datasetId(UUID.randomUUID()).build()));
            case TRACE_ERRORS, TRACE_FEEDBACK_SCORE, TRACE_THREAD_FEEDBACK_SCORE, TRACE_COST, TRACE_LATENCY ->
                List.of(MetricsAlertPayload.builder()
                        .metricValue("10")
                        .threshold("5")
                        .windowSeconds(3600)
                        .build());
        };
    }
}
