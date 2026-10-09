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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertWebhookSenderTest {

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
        lenient().when(eventBridgePublisher.publish(any(), anyString(), anyString(), any(), anyMap()))
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

        verify(eventBridgePublisher).publish(eq(alert), eq(WORKSPACE_ID), eq(WORKSPACE_NAME),
                eq(AlertEventType.TRACE_COST), anyMap());
        verify(webhookPublisher).publishWebhookEvent(eq(AlertEventType.TRACE_COST), eq(alert), eq(WORKSPACE_ID),
                eq(WORKSPACE_NAME), anyMap(), anyInt());
    }

    @Test
    void createAndSendWebhookWhenWebhookUrlEmptyStillPublishesToEventBridge() {
        var alert = alert(true, "");

        StepVerifier.create(send(alert, WORKSPACE_NAME)).verifyComplete();

        verify(eventBridgePublisher).publish(eq(alert), eq(WORKSPACE_ID), eq(WORKSPACE_NAME),
                eq(AlertEventType.TRACE_COST), anyMap());
        verifyNoInteractions(webhookPublisher);
    }

    @Test
    void createAndSendWebhookWhenAlertDisabledPublishesNothing() {
        var alert = alert(false, "https://example.com/hook");

        StepVerifier.create(send(alert, WORKSPACE_NAME)).verifyComplete();

        verify(eventBridgePublisher, never()).publish(any(), anyString(), anyString(), any(), anyMap());
        verifyNoInteractions(webhookPublisher);
    }

    @Test
    void createAndSendWebhookWhenEventBridgePublishThrowsStillSendsWebhook() {
        when(eventBridgePublisher.publish(any(), anyString(), anyString(), any(), anyMap()))
                .thenThrow(new IllegalStateException("boom"));
        var alert = alert(true, "https://example.com/hook");

        StepVerifier.create(send(alert, WORKSPACE_NAME)).verifyComplete();

        verify(webhookPublisher).publishWebhookEvent(eq(AlertEventType.TRACE_COST), eq(alert), eq(WORKSPACE_ID),
                eq(WORKSPACE_NAME), anyMap(), anyInt());
    }

    @Test
    void createAndSendWebhookWhenEventBridgePublishErrorsStillSendsWebhook() {
        when(eventBridgePublisher.publish(any(), anyString(), anyString(), any(), anyMap()))
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

        verify(eventBridgePublisher).publish(eq(alert), eq(WORKSPACE_ID), eq(WORKSPACE_NAME),
                eq(AlertEventType.TRACE_COST), anyMap());
        verify(webhookPublisher).publishWebhookEvent(eq(AlertEventType.TRACE_COST), eq(alert), eq(WORKSPACE_ID),
                eq(WORKSPACE_NAME), anyMap(), anyInt());
    }

    @Test
    void createAndSendWebhookWhenWorkspaceNameLookupFailsStillPublishesToEventBridge() {
        var lookupFailure = new InternalServerErrorException();
        when(workspaceNameService.getWorkspaceName(WORKSPACE_ID, REACT_SERVICE_URL)).thenThrow(lookupFailure);
        when(eventBridgePublisher.publish(any(), anyString(), isNull(), any(), anyMap())).thenReturn(Mono.empty());
        var alert = alert(true, "https://example.com/hook");

        assertThatThrownBy(() -> send(alert, "")).isSameAs(lookupFailure);

        verify(eventBridgePublisher).publish(eq(alert), eq(WORKSPACE_ID), isNull(),
                eq(AlertEventType.TRACE_COST), anyMap());
        verifyNoInteractions(webhookPublisher);
    }

    @Test
    void createAndSendWebhookWhenWorkspaceNameLookupFailsWithoutUrlPublishesToEventBridge() {
        when(workspaceNameService.getWorkspaceName(WORKSPACE_ID, REACT_SERVICE_URL))
                .thenThrow(new InternalServerErrorException());
        when(eventBridgePublisher.publish(any(), anyString(), isNull(), any(), anyMap())).thenReturn(Mono.empty());
        var alert = alert(true, "");

        StepVerifier.create(send(alert, "")).verifyComplete();

        verify(eventBridgePublisher).publish(eq(alert), eq(WORKSPACE_ID), isNull(),
                eq(AlertEventType.TRACE_COST), anyMap());
        verifyNoInteractions(webhookPublisher);
    }

    @Test
    void createAndSendWebhookWhenNoUrlAndEventBridgeDisabledSkips() {
        when(eventBridgePublisher.isEnabled()).thenReturn(false);
        var alert = alert(true, "");

        StepVerifier.create(send(alert, "")).verifyComplete();

        verify(eventBridgePublisher, never()).publish(any(), anyString(), anyString(), any(), anyMap());
        verifyNoInteractions(webhookPublisher, workspaceNameService);
    }

    private Mono<Void> send(Alert alert, String workspaceName) {
        return sender.createAndSendWebhook(alert, WORKSPACE_ID, workspaceName, AlertEventType.TRACE_COST,
                List.of("event-1"), List.of("{}"), List.of("user"));
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
