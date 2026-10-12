package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookPublisher;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.ws.rs.InternalServerErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertWebhookSenderTest {

    private static final String TRIGGER_KEY = "alert-id:trace:cost:1700000000000";
    private static final String EVENT_ID = UUID.nameUUIDFromBytes(
            ("opik-alert:" + TRIGGER_KEY).getBytes(StandardCharsets.UTF_8)).toString();
    private static final String WORKSPACE_ID = "workspace-id";
    private static final String WORKSPACE_NAME = "workspace-name";
    private static final String REACT_SERVICE_URL = "http://react-svc:8080";

    @Mock
    private WebhookPublisher webhookPublisher;
    @Mock
    private WorkspaceNameService workspaceNameService;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private OpikConfiguration config;
    @Mock
    private AlertEventBridgePublisher eventBridgePublisher;

    private AlertWebhookSender sender;

    @BeforeEach
    void setUp() {
        lenient().when(eventBridgePublisher.isEnabled()).thenReturn(true);
        lenient().when(eventBridgePublisher.publish(any(), anyString(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(Mono.empty());
        lenient().when(webhookPublisher.publishWebhookEvent(any(), any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(Mono.just("webhook-id"));
        lenient().when(config.getAuthentication().getReactService().url()).thenReturn(REACT_SERVICE_URL);

        sender = new AlertWebhookSender(webhookPublisher, workspaceNameService, config, eventBridgePublisher);
    }

    @Test
    void createAndSendWebhookPublishesToEventBridgeAndWebhook() {
        var alert = alert(true, "https://example.com/hook");

        StepVerifier.create(send(alert, WORKSPACE_NAME)).verifyComplete();

        verify(eventBridgePublisher).publish(eq(alert), eq(EVENT_ID), eq(WORKSPACE_ID), eq(WORKSPACE_NAME),
                eq(AlertEventType.TRACE_COST), anyMap());
        verify(webhookPublisher).publishWebhookEvent(eq(AlertEventType.TRACE_COST), eq(alert), eq(WORKSPACE_ID),
                eq(WORKSPACE_NAME), anyMap(), anyInt());
    }

    @Test
    void createAndSendWebhookWhenSameTriggerIsReprocessedReusesEventId() {
        var alert = alert(true, "https://example.com/hook");

        StepVerifier.create(send(alert, WORKSPACE_NAME, TRIGGER_KEY)).verifyComplete();
        StepVerifier.create(send(alert, WORKSPACE_NAME, TRIGGER_KEY)).verifyComplete();
        StepVerifier.create(send(alert, WORKSPACE_NAME, "alert-id:trace:cost:1700000060000")).verifyComplete();

        var eventIds = ArgumentCaptor.forClass(String.class);
        verify(eventBridgePublisher, times(3)).publish(eq(alert), eventIds.capture(), eq(WORKSPACE_ID),
                eq(WORKSPACE_NAME), eq(AlertEventType.TRACE_COST), anyMap());
        assertThat(eventIds.getAllValues()).hasSize(3);
        assertThat(eventIds.getAllValues().get(0)).isEqualTo(EVENT_ID).isEqualTo(eventIds.getAllValues().get(1));
        assertThat(eventIds.getAllValues().get(2)).isNotEqualTo(EVENT_ID);
    }

    @Test
    void createAndSendWebhookWhenEventBridgeDisabledOnlySendsWebhook() {
        when(eventBridgePublisher.isEnabled()).thenReturn(false);
        var alert = alert(true, "https://example.com/hook");

        StepVerifier.create(send(alert, WORKSPACE_NAME)).verifyComplete();

        verify(eventBridgePublisher, never()).publish(any(), anyString(), anyString(), anyString(), any(), anyMap());
        verify(webhookPublisher).publishWebhookEvent(eq(AlertEventType.TRACE_COST), eq(alert), eq(WORKSPACE_ID),
                eq(WORKSPACE_NAME), anyMap(), anyInt());
    }

    @Test
    void createAndSendWebhookWhenWebhookUrlEmptyStillPublishesToEventBridge() {
        var alert = alert(true, "");

        StepVerifier.create(send(alert, WORKSPACE_NAME)).verifyComplete();

        verify(eventBridgePublisher).publish(eq(alert), eq(EVENT_ID), eq(WORKSPACE_ID), eq(WORKSPACE_NAME),
                eq(AlertEventType.TRACE_COST), anyMap());
        verifyNoInteractions(webhookPublisher);
    }

    @Test
    void createAndSendWebhookWhenAlertDisabledPublishesNothing() {
        var alert = alert(false, "https://example.com/hook");

        StepVerifier.create(send(alert, WORKSPACE_NAME)).verifyComplete();

        verify(eventBridgePublisher, never()).publish(any(), anyString(), anyString(), anyString(), any(), anyMap());
        verifyNoInteractions(webhookPublisher);
    }

    @Test
    void createAndSendWebhookWhenEventBridgePublishThrowsStillSendsWebhook() {
        when(eventBridgePublisher.publish(any(), anyString(), anyString(), anyString(), any(), anyMap()))
                .thenThrow(new IllegalStateException("boom"));
        var alert = alert(true, "https://example.com/hook");

        StepVerifier.create(send(alert, WORKSPACE_NAME)).verifyComplete();

        verify(webhookPublisher).publishWebhookEvent(eq(AlertEventType.TRACE_COST), eq(alert), eq(WORKSPACE_ID),
                eq(WORKSPACE_NAME), anyMap(), anyInt());
    }

    @Test
    void createAndSendWebhookWhenEventBridgePublishErrorsStillSendsWebhook() {
        when(eventBridgePublisher.publish(any(), anyString(), anyString(), anyString(), any(), anyMap()))
                .thenReturn(Mono.error(new IllegalStateException("boom")));
        var alert = alert(true, "https://example.com/hook");

        StepVerifier.create(send(alert, WORKSPACE_NAME)).verifyComplete();

        verify(webhookPublisher).publishWebhookEvent(eq(AlertEventType.TRACE_COST), eq(alert), eq(WORKSPACE_ID),
                eq(WORKSPACE_NAME), anyMap(), anyInt());
    }

    @Test
    void createAndSendWebhookWhenWorkspaceNameBlankResolvesItForBothTargets() {
        when(workspaceNameService.getWorkspaceName(WORKSPACE_ID, REACT_SERVICE_URL)).thenReturn(WORKSPACE_NAME);
        var alert = alert(true, "https://example.com/hook");

        StepVerifier.create(send(alert, "")).verifyComplete();

        verify(eventBridgePublisher).publish(eq(alert), eq(EVENT_ID), eq(WORKSPACE_ID), eq(WORKSPACE_NAME),
                eq(AlertEventType.TRACE_COST), anyMap());
        verify(webhookPublisher).publishWebhookEvent(eq(AlertEventType.TRACE_COST), eq(alert), eq(WORKSPACE_ID),
                eq(WORKSPACE_NAME), anyMap(), anyInt());
    }

    @Test
    void createAndSendWebhookWhenWorkspaceNameLookupFailsStillPublishesToEventBridge() {
        var lookupFailure = new InternalServerErrorException();
        when(workspaceNameService.getWorkspaceName(WORKSPACE_ID, REACT_SERVICE_URL)).thenThrow(lookupFailure);
        when(eventBridgePublisher.publish(any(), anyString(), anyString(), isNull(), any(), anyMap()))
                .thenReturn(Mono.empty());
        var alert = alert(true, "https://example.com/hook");

        assertThatThrownBy(() -> send(alert, "")).isSameAs(lookupFailure);

        verify(eventBridgePublisher).publish(eq(alert), eq(EVENT_ID), eq(WORKSPACE_ID), isNull(),
                eq(AlertEventType.TRACE_COST), anyMap());
        verifyNoInteractions(webhookPublisher);
    }

    @Test
    void createAndSendWebhookWhenWorkspaceNameLookupFailsWithoutUrlPublishesToEventBridge() {
        when(workspaceNameService.getWorkspaceName(WORKSPACE_ID, REACT_SERVICE_URL))
                .thenThrow(new InternalServerErrorException());
        when(eventBridgePublisher.publish(any(), anyString(), anyString(), isNull(), any(), anyMap()))
                .thenReturn(Mono.empty());
        var alert = alert(true, "");

        StepVerifier.create(send(alert, "")).verifyComplete();

        verify(eventBridgePublisher).publish(eq(alert), eq(EVENT_ID), eq(WORKSPACE_ID), isNull(),
                eq(AlertEventType.TRACE_COST), anyMap());
        verifyNoInteractions(webhookPublisher);
    }

    @Test
    void createAndSendWebhookWhenNoUrlAndEventBridgeDisabledSkips() {
        when(eventBridgePublisher.isEnabled()).thenReturn(false);
        var alert = alert(true, "");

        StepVerifier.create(send(alert, "")).verifyComplete();

        verify(eventBridgePublisher, never()).publish(any(), anyString(), anyString(), anyString(), any(), anyMap());
        verifyNoInteractions(webhookPublisher, workspaceNameService);
    }

    private Mono<Void> send(Alert alert, String workspaceName) {
        return send(alert, workspaceName, TRIGGER_KEY);
    }

    private Mono<Void> send(Alert alert, String workspaceName, String triggerKey) {
        return sender.createAndSendWebhook(alert, WORKSPACE_ID, workspaceName, AlertEventType.TRACE_COST,
                List.of("event-1"), List.of("{}"), List.of("user"), triggerKey);
    }

    private static Alert alert(boolean enabled, String webhookUrl) {
        return Alert.builder()
                .id(UUID.randomUUID())
                .name("cost alert")
                .enabled(enabled)
                .webhook(Webhook.builder().url(webhookUrl).build())
                .workspaceId(WORKSPACE_ID)
                .build();
    }
}
