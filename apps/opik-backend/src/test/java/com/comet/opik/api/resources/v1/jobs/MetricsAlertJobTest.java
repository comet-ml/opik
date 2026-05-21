package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.Webhook;
import com.comet.opik.domain.AlertService;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectMetricsDAO;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.alerts.AlertWebhookSender;
import com.comet.opik.infrastructure.WebhookConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.AlertTriggerConfig.NAME_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.OPERATOR_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.THRESHOLD_CONFIG_KEY;
import static com.comet.opik.api.AlertTriggerConfig.WINDOW_CONFIG_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the grouped AND-condition behavior introduced on
 * {@link MetricsAlertJob}. Drives the job through the public {@code doJob}
 * entry point and asserts side effects on the mocked
 * {@link AlertWebhookSender}, since the evaluation/payload methods are private.
 */
@ExtendWith(MockitoExtension.class)
class MetricsAlertJobTest {

    private static final String WORKSPACE_ID = "ws-1";
    private static final String FEEDBACK_NAME = "helpfulness";
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final long ASYNC_TIMEOUT_MS = 2_000;
    private static final long NO_CALL_WINDOW_MS = 500;

    @Mock
    private WebhookConfig webhookConfig;
    @Mock
    private LockService lockService;
    @Mock
    private AlertService alertService;
    @Mock
    private ProjectMetricsDAO projectMetricsDAO;
    @Mock
    private ProjectService projectService;
    @Mock
    private IdGenerator idGenerator;
    @Mock
    private AlertWebhookSender alertWebhookSender;

    private MetricsAlertJob job;

    @BeforeEach
    void setUp() {
        WebhookConfig.MetricsConfig metrics = new WebhookConfig.MetricsConfig();
        metrics.setFixedDelay(Duration.seconds(60));
        lenient().when(webhookConfig.getMetrics()).thenReturn(metrics);

        lenient().when(lockService.lockUsingToken(any(), any(java.time.Duration.class)))
                .thenReturn(Mono.just(true));
        lenient().when(idGenerator.generateId()).thenReturn(EVENT_ID);
        lenient().when(projectService.findByIds(anyString(), any())).thenReturn(List.of());
        lenient().when(alertWebhookSender.createAndSendWebhook(
                any(), anyString(), anyString(), any(), anyList(), anyList(), anyList()))
                .thenReturn(Mono.empty());

        job = new MetricsAlertJob(webhookConfig, lockService, alertService, projectMetricsDAO,
                projectService, idGenerator, alertWebhookSender);
    }

    @ParameterizedTest
    @EnumSource(value = AlertEventType.class, names = {"TRACE_FEEDBACK_SCORE", "TRACE_THREAD_FEEDBACK_SCORE"})
    void firesWhenAllConditionsInGroupCross(AlertEventType eventType) {
        // Both conditions are LESS_THAN 0.5 and both metrics cross.
        Alert alert = alertWithGroupedFeedbackConfigs(eventType, 1, "<", "0.5", "0.5");

        stubFeedbackScores(eventType, "0.3", "0.2");
        when(alertService.findAllByWorkspaceAndEventTypes(null,
                MetricsAlertJob.SUPPORTED_EVENT_TYPES)).thenReturn(List.of(alert));

        job.doJob(null);

        verify(alertWebhookSender, timeout(ASYNC_TIMEOUT_MS)).createAndSendWebhook(
                any(), eq(WORKSPACE_ID), anyString(), eq(eventType), anyList(), anyList(), anyList());
    }

    @ParameterizedTest
    @EnumSource(value = AlertEventType.class, names = {"TRACE_FEEDBACK_SCORE", "TRACE_THREAD_FEEDBACK_SCORE"})
    void doesNotFireWhenAnyConditionInGroupFails(AlertEventType eventType) {
        // First crosses (0.4 < 0.5), second does NOT (0.9 < 0.5 is false).
        Alert alert = alertWithGroupedFeedbackConfigs(eventType, 1, "<", "0.5", "0.5");

        stubFeedbackScores(eventType, "0.4", "0.9");
        when(alertService.findAllByWorkspaceAndEventTypes(null,
                MetricsAlertJob.SUPPORTED_EVENT_TYPES)).thenReturn(List.of(alert));

        job.doJob(null);

        verify(alertWebhookSender, after(NO_CALL_WINDOW_MS).never()).createAndSendWebhook(
                any(), anyString(), anyString(), any(), anyList(), anyList(), anyList());
    }

    @ParameterizedTest
    @EnumSource(value = AlertEventType.class, names = {"TRACE_FEEDBACK_SCORE", "TRACE_THREAD_FEEDBACK_SCORE"})
    void skipsGroupWhenAtLeastOneMetricMissing(AlertEventType eventType) {
        Alert alert = alertWithGroupedFeedbackConfigs(eventType, 1, "<", "0.5", "0.5");

        EntityType entityType = entityTypeFor(eventType);
        // First condition would cross; second has no data (empty Mono).
        when(projectMetricsDAO.getAverageFeedbackScore(
                anyList(), any(Instant.class), any(Instant.class), eq(entityType), eq(FEEDBACK_NAME)))
                .thenReturn(Mono.just(new BigDecimal("0.1")))
                .thenReturn(Mono.empty());
        when(alertService.findAllByWorkspaceAndEventTypes(null,
                MetricsAlertJob.SUPPORTED_EVENT_TYPES)).thenReturn(List.of(alert));

        job.doJob(null);

        verify(alertWebhookSender, after(NO_CALL_WINDOW_MS).never()).createAndSendWebhook(
                any(), anyString(), anyString(), any(), anyList(), anyList(), anyList());
    }

    @ParameterizedTest
    @EnumSource(value = AlertEventType.class, names = {"TRACE_FEEDBACK_SCORE", "TRACE_THREAD_FEEDBACK_SCORE"})
    void payloadContainsGroupIndexAndConditionsWithScalarsFromFirstCondition(AlertEventType eventType) {
        int groupIndex = 7;
        Alert alert = alertWithGroupedFeedbackConfigs(eventType, groupIndex, "<", "0.5", "0.6");

        stubFeedbackScores(eventType, "0.1", "0.2");
        when(alertService.findAllByWorkspaceAndEventTypes(null,
                MetricsAlertJob.SUPPORTED_EVENT_TYPES)).thenReturn(List.of(alert));

        job.doJob(null);

        ArgumentCaptor<List<String>> payloadCaptor = listCaptor();
        verify(alertWebhookSender, timeout(ASYNC_TIMEOUT_MS)).createAndSendWebhook(
                any(), eq(WORKSPACE_ID), anyString(), eq(eventType), anyList(),
                payloadCaptor.capture(), anyList());

        List<String> payloads = payloadCaptor.getValue();
        assertThat(payloads).hasSize(1);

        JsonNode payload = JsonUtils.readValue(payloads.getFirst(), JsonNode.class);

        assertThat(payload.get("group_index").asInt()).isEqualTo(groupIndex);

        // Scalar fields are taken from the first contributing condition for back-compat.
        // NumberUtils.formatDecimal renders to 4 decimal places.
        assertThat(payload.get("threshold").asText()).isEqualTo("0.5000");
        assertThat(payload.get("metric_value").asText()).isEqualTo("0.1000");
        assertThat(payload.get("metric_name").asText()).isEqualTo(eventType.getValue());
        assertThat(payload.get("feedback_score_name").asText()).isEqualTo(FEEDBACK_NAME);

        JsonNode conditions = payload.get("conditions");
        assertThat(conditions.isArray()).isTrue();
        assertThat(conditions).hasSize(2);

        assertThat(conditions.get(0).get("threshold").asText()).isEqualTo("0.5000");
        assertThat(conditions.get(0).get("metric_value").asText()).isEqualTo("0.1000");
        assertThat(conditions.get(0).get("feedback_score_name").asText()).isEqualTo(FEEDBACK_NAME);
        assertThat(conditions.get(0).get("operator").asText()).isEqualTo("<");

        assertThat(conditions.get(1).get("threshold").asText()).isEqualTo("0.6000");
        assertThat(conditions.get(1).get("metric_value").asText()).isEqualTo("0.2000");
    }

    @Test
    void doesNotEvaluateWhenInterrupted() throws org.quartz.UnableToInterruptJobException {
        job.interrupt();
        job.doJob(null);

        verify(alertService, never()).findAllByWorkspaceAndEventTypes(any(), any());
        verify(alertWebhookSender, never()).createAndSendWebhook(
                any(), anyString(), anyString(), any(), anyList(), anyList(), anyList());
    }

    // --- helpers ---------------------------------------------------------

    private void stubFeedbackScores(AlertEventType eventType, String v1, String v2) {
        EntityType entityType = entityTypeFor(eventType);
        when(projectMetricsDAO.getAverageFeedbackScore(
                anyList(), any(Instant.class), any(Instant.class), eq(entityType), eq(FEEDBACK_NAME)))
                .thenReturn(Mono.just(new BigDecimal(v1)))
                .thenReturn(Mono.just(new BigDecimal(v2)));
    }

    private static EntityType entityTypeFor(AlertEventType eventType) {
        return eventType == AlertEventType.TRACE_THREAD_FEEDBACK_SCORE ? EntityType.THREAD : EntityType.TRACE;
    }

    private static Alert alertWithGroupedFeedbackConfigs(AlertEventType eventType, int groupIndex,
            String operator, String threshold1, String threshold2) {
        AlertTrigger trigger = AlertTrigger.builder()
                .id(UUID.randomUUID())
                .eventType(eventType)
                .triggerConfigs(List.of(
                        feedbackConfig(operator, threshold1, groupIndex),
                        feedbackConfig(operator, threshold2, groupIndex)))
                .build();

        return Alert.builder()
                .id(UUID.randomUUID())
                .name("test-alert")
                .enabled(true)
                .webhook(Webhook.builder().url("http://example/hook").build())
                .triggers(List.of(trigger))
                .projectId(PROJECT_ID)
                .workspaceId(WORKSPACE_ID)
                .build();
    }

    private static AlertTriggerConfig feedbackConfig(String operator, String threshold, Integer groupIndex) {
        return AlertTriggerConfig.builder()
                .id(UUID.randomUUID())
                .type(AlertTriggerConfigType.THRESHOLD_FEEDBACK_SCORE)
                .configValue(Map.of(
                        NAME_CONFIG_KEY, FEEDBACK_NAME,
                        OPERATOR_CONFIG_KEY, operator,
                        THRESHOLD_CONFIG_KEY, threshold,
                        WINDOW_CONFIG_KEY, "300"))
                .groupIndex(groupIndex)
                .build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<String>> listCaptor() {
        return (ArgumentCaptor<List<String>>) (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}