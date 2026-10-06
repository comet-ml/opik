package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.infrastructure.AlertsEventBridgeConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertEventBridgePublisherTest {

    private static final String EVENT_BUS = "arn:aws:events:us-east-1:123456789012:event-bus/opik-alerts";
    private static final String WORKSPACE_ID = "workspace-id";
    private static final String WORKSPACE_NAME = "workspace-name";
    private static final UUID PROJECT_ID = UUID.randomUUID();
    // Longer than every backoff of the bounded retry, jitter included
    private static final Duration RETRY_WINDOW = Duration.ofSeconds(10);

    @Mock
    private EventBridgeClient client;

    private AlertEventBridgePublisher publisher;

    @BeforeEach
    void setUp() {
        var config = new AlertsEventBridgeConfig();
        config.setEventBus(EVENT_BUS);
        publisher = new AlertEventBridgePublisher(Optional.of(client), config);
    }

    @Test
    void publish() {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(Map.of("base_url", "https://opik.example.com/opik/"));
        var metricsPayload = MetricsAlertPayload.builder()
                .metricName("cost")
                .metricValue("12.5")
                .threshold("10")
                .windowSeconds(3600)
                .build();
        var payload = payload(alert, List.of("event-1"), List.of(JsonUtils.writeValueAsString(metricsPayload)));

        StepVerifier.create(publisher.publish(alert, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                payload)).verifyComplete();

        var entry = captureEntries(1).getFirst();
        assertThat(entry.source()).isEqualTo("comet.opik");
        assertThat(entry.detailType()).isEqualTo("Opik Alert");
        assertThat(entry.eventBusName()).isEqualTo(EVENT_BUS);
        assertThat(entry.time()).isNotNull();

        JsonNode detail = JsonUtils.getJsonNodeFromString(entry.detail());
        assertThat(detail.get("schemaVersion").asText()).isEqualTo("1");
        assertThat(detail.get("workspaceId").asText()).isEqualTo(WORKSPACE_ID);
        assertThat(detail.get("workspaceName").asText()).isEqualTo(WORKSPACE_NAME);
        assertThat(detail.get("alertId").asText()).isEqualTo(alert.id().toString());
        assertThat(detail.get("alertName").asText()).isEqualTo(alert.name());
        assertThat(detail.get("eventType").asText()).isEqualTo(AlertEventType.TRACE_COST.getValue());
        assertThat(detail.get("url").asText()).isEqualTo("https://opik.example.com/opik/%s/projects/%s/alerts/%s"
                .formatted(WORKSPACE_NAME, PROJECT_ID, alert.id()));
        assertThat(detail.get("triggeredAt").asText()).isEqualTo(entry.time().toString());
        assertThat(detail.get("truncated").asBoolean()).isFalse();

        JsonNode opik = detail.get("opik");
        assertThat(opik.get("eventIds")).containsExactly(JsonUtils.valueToTree("event-1"));
        assertThat(opik.get("eventCount").asInt()).isEqualTo(1);
        assertThat(opik.get("userNames")).containsExactly(JsonUtils.valueToTree("user"));
        assertThat(opik.get("message").asText()).isEqualTo(payload.get("message"));
        assertThat(JsonUtils.treeToValue(opik.get("metadata").get(0), MetricsAlertPayload.class))
                .isEqualTo(metricsPayload);
    }

    @Test
    void publishWhenNoBaseUrlOmitsUrl() {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(null);

        StepVerifier.create(publisher.publish(alert, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                payload(alert, List.of("event-1"), List.of()))).verifyComplete();

        JsonNode detail = JsonUtils.getJsonNodeFromString(captureEntries(1).getFirst().detail());
        assertThat(detail.has("url")).isFalse();
    }

    @Test
    void publishWhenDetailExceedsLimitTruncates() {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(null);
        var eventIds = IntStream.range(0, 5_000).mapToObj(i -> UUID.randomUUID().toString()).toList();
        var largeMetadata = JsonUtils.writeValueAsString(MetricsAlertPayload.builder()
                .metricValue("1")
                .threshold("x".repeat(300_000))
                .build());

        StepVerifier.create(publisher.publish(alert, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                payload(alert, eventIds, List.of(largeMetadata)))).verifyComplete();

        var entry = captureEntries(1).getFirst();
        assertThat(entry.detail().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(AlertEventBridgePublisher.MAX_DETAIL_BYTES);

        JsonNode detail = JsonUtils.getJsonNodeFromString(entry.detail());
        assertThat(detail.get("truncated").asBoolean()).isTrue();
        JsonNode opik = detail.get("opik");
        assertThat(opik.get("metadata")).isEmpty();
        assertThat(opik.get("eventIds")).hasSize(AlertEventBridgePublisher.MAX_TRUNCATED_LIST_SIZE);
        assertThat(opik.get("eventCount").asInt()).isEqualTo(eventIds.size());
    }

    @Test
    void publishWhenClientThrowsSwallowsError() {
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenThrow(new IllegalStateException("boom"));
        var alert = alert(null);

        StepVerifier.create(publisher.publish(alert, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                payload(alert, List.of("event-1"), List.of()))).verifyComplete();

        verify(client, times(1)).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void publishWhenThrottledRetriesThenSwallowsError() {
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenThrow(EventBridgeException.builder().statusCode(503).message("unavailable").build());
        var alert = alert(null);

        StepVerifier.withVirtualTime(() -> publisher.publish(alert, WORKSPACE_ID, WORKSPACE_NAME,
                AlertEventType.TRACE_COST, payload(alert, List.of("event-1"), List.of())))
                .thenAwait(RETRY_WINDOW)
                .verifyComplete();

        verify(client, times(3)).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void publishWhenEntryThrottledRetriesUntilAccepted() {
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenReturn(PutEventsResponse.builder()
                        .failedEntryCount(1)
                        .entries(PutEventsResultEntry.builder().errorCode("ThrottlingException").build())
                        .build())
                .thenReturn(successResponse());
        var alert = alert(null);

        StepVerifier.withVirtualTime(() -> publisher.publish(alert, WORKSPACE_ID, WORKSPACE_NAME,
                AlertEventType.TRACE_COST, payload(alert, List.of("event-1"), List.of())))
                .thenAwait(RETRY_WINDOW)
                .verifyComplete();

        verify(client, times(2)).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void publishWhenEntryRejectedDoesNotRetry() {
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenReturn(PutEventsResponse.builder()
                        .failedEntryCount(1)
                        .entries(PutEventsResultEntry.builder().errorCode("MalformedDetail").build())
                        .build());
        var alert = alert(null);

        StepVerifier.create(publisher.publish(alert, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                payload(alert, List.of("event-1"), List.of()))).verifyComplete();

        verify(client, times(1)).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void publishWhenDisabledDoesNothing() {
        var disabled = new AlertEventBridgePublisher(Optional.empty(), new AlertsEventBridgeConfig());
        var alert = alert(null);

        StepVerifier.create(disabled.publish(alert, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                payload(alert, List.of("event-1"), List.of()))).verifyComplete();

        assertThat(disabled.isEnabled()).isFalse();
        verifyNoInteractions(client);
    }

    private List<PutEventsRequestEntry> captureEntries(int calls) {
        var captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(client, times(calls)).putEvents(captor.capture());
        return captor.getValue().entries();
    }

    private static PutEventsResponse successResponse() {
        return PutEventsResponse.builder()
                .failedEntryCount(0)
                .entries(PutEventsResultEntry.builder().eventId(UUID.randomUUID().toString()).build())
                .build();
    }

    private static Alert alert(Map<String, String> metadata) {
        return Alert.builder()
                .id(UUID.randomUUID())
                .name("cost alert")
                .enabled(true)
                .metadata(metadata)
                .projectId(PROJECT_ID)
                .webhook(Webhook.builder().url("").build())
                .workspaceId(WORKSPACE_ID)
                .build();
    }

    private static Map<String, Object> payload(Alert alert, List<String> eventIds, List<String> metadata) {
        return Map.of(
                "alertId", alert.id().toString(),
                "alertName", alert.name(),
                "eventType", AlertEventType.TRACE_COST.getValue(),
                "eventIds", eventIds,
                "metadata", metadata,
                "userNames", List.of("user"),
                "eventCount", eventIds.size(),
                "aggregationType", "consolidated",
                "message", "Alert '%s': %d trace:cost events aggregated".formatted(alert.name(), eventIds.size()));
    }
}
