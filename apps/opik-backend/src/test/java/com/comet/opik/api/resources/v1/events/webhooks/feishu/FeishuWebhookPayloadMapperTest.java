package com.comet.opik.api.resources.v1.events.webhooks.feishu;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuWebhookPayloadMapperTest {

    private static final String ALERT_NAME = "Test Feishu Alert";
    private static final String BASE_URL = "http://localhost:5555";
    private static final String WORKSPACE_NAME = "default";

    private WebhookEvent<Map<String, Object>> buildEvent(
            AlertEventType eventType, List<?> metadata) {
        return WebhookEvent.<Map<String, Object>>builder()
                .id("event-" + System.currentTimeMillis())
                .eventType(eventType)
                .alertType(AlertType.FEISHU)
                .alertId(UUID.randomUUID())
                .alertName(ALERT_NAME)
                .alertMetadata(Map.of(FeishuWebhookPayloadMapper.BASE_URL_METADATA_KEY, BASE_URL))
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
            assertThat(payload.card().header().title().content()).contains("Opik Alert:");
            assertThat(payload.card().header().title().content()).contains(ALERT_NAME);
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
            assertThat(content).contains("**1** new Prompt Created event happened");
            assertThat(content).contains("**Prompts Created:**");
            assertThat(content).contains(promptId.toString());
            assertThat(content).contains("[View]");
        }

        @Test
        void promptDeleted() {
            UUID promptId = UUID.randomUUID();
            var event = buildEvent(AlertEventType.PROMPT_DELETED,
                    List.of(List.of(Prompt.builder().id(promptId).name("deleted-prompt").build())));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).contains("**Deleted Prompt IDs:**");
            assertThat(content).contains(promptId.toString());
        }

        @Test
        void promptCommitted() {
            UUID promptId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            var event = buildEvent(AlertEventType.PROMPT_COMMITTED,
                    List.of(PromptVersion.builder().id(versionId).promptId(promptId).build()));

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            String content = payload.card().elements().get(0).text().content();
            assertThat(content).contains("**Prompts Committed:**");
            assertThat(content).contains(promptId.toString());
            assertThat(content).contains(versionId.toString());
            assertThat(content).contains("[View]");
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
            assertThat(content).contains("**Trace Errors Alert Triggered:**");
            assertThat(content).contains("**Current Trace Errors:** 15");
            assertThat(content).contains("**Threshold:** 10");
            assertThat(content).contains("**Time Window:** 1 hour");
            assertThat(content).contains("*Workspace-wide*");
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
            assertThat(content).contains("**Trace Feedback Score Alert Triggered:**");
            assertThat(content).contains("0.85");
            assertThat(content).contains("0.9");
            assertThat(content).contains("1 day");
            assertThat(content).contains("`accuracy`");
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
            assertThat(content).contains("**Thread Feedback Score Alert Triggered:**");
            assertThat(content).contains("2 hours");
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
            assertThat(content).contains("**Cost Alert Triggered:**");
            assertThat(content).contains("$150.50");
            assertThat(content).contains("$100.00");
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
            assertThat(content).contains("**Latency Alert Triggered:**");
            assertThat(content).contains("5.2 s");
            assertThat(content).contains("3.0 s");
            assertThat(content).contains("30 minutes");
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
            assertThat(content).contains("**Traces with Guardrails Triggered:**");
            assertThat(content).contains(traceId.toString());
            assertThat(content).contains("[View]");
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
            assertThat(content).contains("**Experiments Finished:**");
            assertThat(content).contains(experimentId.toString());
            assertThat(content).contains("[View]");
        }
    }

    @Nested
    class DefaultBaseUrl {

        @Test
        void shouldUseDefaultBaseUrlWhenMetadataEmpty() {
            var event = WebhookEvent.<Map<String, Object>>builder()
                    .id("event-1")
                    .eventType(AlertEventType.PROMPT_CREATED)
                    .alertType(AlertType.FEISHU)
                    .alertId(UUID.randomUUID())
                    .alertName(ALERT_NAME)
                    .alertMetadata(Map.of())
                    .workspaceId("workspace-1")
                    .workspaceName(WORKSPACE_NAME)
                    .userName("test-user")
                    .payload(Map.of("metadata",
                            List.of(Prompt.builder().id(UUID.randomUUID()).name("p1").build())))
                    .createdAt(Instant.now())
                    .url("https://open.feishu.cn/open-apis/bot/v2/hook/test")
                    .headers(Map.of())
                    .build();

            FeishuWebhookPayload payload = FeishuWebhookPayloadMapper.toFeishuPayload(event);

            // Should not throw and should contain default URL
            String content = payload.card().elements().get(0).text().content();
            assertThat(content).contains("http://localhost:5173");
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
            assertThat(actionElement.actions().get(0).url()).contains("/projects/" + projectId);
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
            assertThat(actionElement.actions().get(0).url()).endsWith("/projects");
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
            assertThat(actionElement.actions().get(0).url()).contains("type=threads");
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
            assertThat(content).contains(expectedMessage);
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
