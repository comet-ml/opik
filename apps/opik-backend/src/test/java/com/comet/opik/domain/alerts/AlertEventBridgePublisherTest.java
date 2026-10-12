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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
    private static final String EVENT_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = "workspace-id";
    private static final Instant TRIGGERED_AT = Instant.parse("2026-10-01T12:00:00Z");
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

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT,
                payload)).verifyComplete();

        var entry = captureEntries(1).getFirst();
        assertThat(entry.source()).isEqualTo("comet.opik");
        assertThat(entry.detailType()).isEqualTo("Opik Alert");
        assertThat(entry.eventBusName()).isEqualTo(EVENT_BUS);
        assertThat(entry.time()).isEqualTo(TRIGGERED_AT);

        JsonNode detail = JsonUtils.getJsonNodeFromString(entry.detail());
        assertThat(detail.get("schemaVersion").asText()).isEqualTo("1");
        assertThat(detail.get("eventId").asText()).isEqualTo(EVENT_ID);
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
    void publishWhenSameTriggerIsPublishedTwiceKeepsTriggeredAt() {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(null);
        var payload = payload(alert, List.of("event-1"), List.of());

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT, payload)).verifyComplete();
        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT, payload)).verifyComplete();

        var entries = captureAllEntries(2);
        assertThat(entries).extracting(PutEventsRequestEntry::time).containsExactly(TRIGGERED_AT, TRIGGERED_AT);
        assertThat(entries)
                .extracting(entry -> JsonUtils.getJsonNodeFromString(entry.detail()).get("triggeredAt").asText())
                .containsExactly(TRIGGERED_AT.toString(), TRIGGERED_AT.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)//", "/opik/", "opik.example.com", "ftp://opik.example.com/",
            "https:///no-host", "http://exa mple.com/"})
    void publishWhenBaseUrlIsNotAbsoluteHttpSendsNullUrl(String baseUrl) {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(Map.of("base_url", baseUrl));

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT, payload(alert, List.of("event-1"), List.of()))).verifyComplete();

        JsonNode detail = JsonUtils.getJsonNodeFromString(captureEntries(1).getFirst().detail());
        assertThat(detail.has("url")).isTrue();
        assertThat(detail.get("url").isNull()).isTrue();
    }

    @Test
    void publishEncodesWorkspaceNameInUrl() {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(Map.of("base_url", "https://opik.example.com"));

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, "my team/a?b#c", AlertEventType.TRACE_COST,
                TRIGGERED_AT, payload(alert, List.of("event-1"), List.of()))).verifyComplete();

        JsonNode detail = JsonUtils.getJsonNodeFromString(captureEntries(1).getFirst().detail());
        assertThat(detail.get("url").asText()).isEqualTo("https://opik.example.com/my%20team%2Fa%3Fb%23c/projects/"
                + PROJECT_ID + "/alerts/" + alert.id());
    }

    @Test
    void publishWhenNoBaseUrlSendsNullUrl() {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(null);

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT,
                payload(alert, List.of("event-1"), List.of()))).verifyComplete();

        JsonNode detail = JsonUtils.getJsonNodeFromString(captureEntries(1).getFirst().detail());
        assertThat(detail.has("url")).isTrue();
        assertThat(detail.get("url").isNull()).isTrue();
    }

    @Test
    void publishWhenWorkspaceNameNullSendsNullNameAndUrl() {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(Map.of("base_url", "https://opik.example.com/"));

        StepVerifier
                .create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, null, AlertEventType.TRACE_COST, TRIGGERED_AT,
                        payload(alert, List.of("event-1"), List.of())))
                .verifyComplete();

        JsonNode detail = JsonUtils.getJsonNodeFromString(captureEntries(1).getFirst().detail());
        assertThat(detail.get("workspaceId").asText()).isEqualTo(WORKSPACE_ID);
        assertThat(detail.has("workspaceName")).isTrue();
        assertThat(detail.get("workspaceName").isNull()).isTrue();
        assertThat(detail.has("url")).isTrue();
        assertThat(detail.get("url").isNull()).isTrue();
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

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT,
                payload(alert, eventIds, List.of(largeMetadata)))).verifyComplete();

        var entry = captureEntries(1).getFirst();
        assertThat(AlertEventBridgePublisher.entrySize(entry.detail()))
                .isLessThanOrEqualTo(AlertEventBridgePublisher.MAX_ENTRY_BYTES);

        JsonNode detail = JsonUtils.getJsonNodeFromString(entry.detail());
        assertThat(detail.get("truncated").asBoolean()).isTrue();
        assertThat(detail.get("eventId").asText()).isEqualTo(EVENT_ID);
        JsonNode opik = detail.get("opik");
        assertThat(opik.get("metadata")).isEmpty();
        assertThat(opik.get("eventIds")).hasSize(AlertEventBridgePublisher.MAX_TRUNCATED_LIST_SIZE);
        assertThat(opik.get("eventCount").asInt()).isEqualTo(eventIds.size());
    }

    @Test
    void publishWhenEntryFitsWithoutCountingBusNameDoesNotTruncate() {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(null);
        // Over the old 250,000-byte detail budget, yet within 256 KiB once only the counted fields are summed
        var eventIds = IntStream.range(0, 850).mapToObj(i -> i + "-" + "x".repeat(300)).toList();

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT,
                payload(alert, eventIds, List.of()))).verifyComplete();

        var entry = captureEntries(1).getFirst();
        assertThat(entry.detail().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(250_000);
        assertThat(AlertEventBridgePublisher.entrySize(entry.detail()))
                .isLessThanOrEqualTo(AlertEventBridgePublisher.MAX_ENTRY_BYTES);
        JsonNode detail = JsonUtils.getJsonNodeFromString(entry.detail());
        assertThat(detail.get("truncated").asBoolean()).isFalse();
        assertThat(detail.get("opik").get("eventIds")).hasSize(eventIds.size());
    }

    @Test
    void publishWhenDetailStillExceedsLimitAfterCappingDropsLists() {
        when(client.putEvents(any(PutEventsRequest.class))).thenReturn(successResponse());
        var alert = alert(null);
        var eventIds = IntStream.range(0, 2_000).mapToObj(i -> i + "-" + "x".repeat(300)).toList();

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT,
                payload(alert, eventIds, List.of()))).verifyComplete();

        var entry = captureEntries(1).getFirst();
        assertThat(AlertEventBridgePublisher.entrySize(entry.detail()))
                .isLessThanOrEqualTo(AlertEventBridgePublisher.MAX_ENTRY_BYTES);

        JsonNode detail = JsonUtils.getJsonNodeFromString(entry.detail());
        assertThat(detail.get("truncated").asBoolean()).isTrue();
        assertThat(detail.get("eventId").asText()).isEqualTo(EVENT_ID);
        JsonNode opik = detail.get("opik");
        assertThat(opik.get("eventIds")).isEmpty();
        assertThat(opik.get("userNames")).isEmpty();
        assertThat(opik.get("metadata")).isEmpty();
        assertThat(opik.get("eventCount").asInt()).isEqualTo(eventIds.size());
    }

    @Test
    void publishWhenClientThrowsSwallowsError() {
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenThrow(new IllegalStateException("boom"));
        var alert = alert(null);

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT,
                payload(alert, List.of("event-1"), List.of()))).verifyComplete();

        verify(client, times(1)).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void publishWhenThrottledRetriesThenSwallowsError() {
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenThrow(EventBridgeException.builder().statusCode(503).message("unavailable").build());
        var alert = alert(null);

        StepVerifier.withVirtualTime(() -> publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME,
                AlertEventType.TRACE_COST, TRIGGERED_AT, payload(alert, List.of("event-1"), List.of())))
                .thenAwait(RETRY_WINDOW)
                .verifyComplete();

        verify(client, times(3)).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void publishWhenClientErrorIsTransientRetriesUntilAccepted() {
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request: Read timed out"))
                .thenReturn(successResponse());
        var alert = alert(null);

        StepVerifier.withVirtualTime(() -> publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME,
                AlertEventType.TRACE_COST, TRIGGERED_AT, payload(alert, List.of("event-1"), List.of())))
                .thenAwait(RETRY_WINDOW)
                .verifyComplete();

        verify(client, times(2)).putEvents(any(PutEventsRequest.class));
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

        StepVerifier.withVirtualTime(() -> publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME,
                AlertEventType.TRACE_COST, TRIGGERED_AT, payload(alert, List.of("event-1"), List.of())))
                .thenAwait(RETRY_WINDOW)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(client, times(2)).putEvents(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(request -> JsonUtils.getJsonNodeFromString(request.entries().getFirst().detail())
                        .get("eventId").asText())
                .containsExactly(EVENT_ID, EVENT_ID);
    }

    @Test
    void publishWhenEntryRejectedDoesNotRetry() {
        when(client.putEvents(any(PutEventsRequest.class)))
                .thenReturn(PutEventsResponse.builder()
                        .failedEntryCount(1)
                        .entries(PutEventsResultEntry.builder().errorCode("MalformedDetail").build())
                        .build());
        var alert = alert(null);

        StepVerifier.create(publisher.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST,
                TRIGGERED_AT,
                payload(alert, List.of("event-1"), List.of()))).verifyComplete();

        verify(client, times(1)).putEvents(any(PutEventsRequest.class));
    }

    @Test
    void publishWhenDisabledDoesNothing() {
        var disabled = new AlertEventBridgePublisher(Optional.empty(), new AlertsEventBridgeConfig());
        var alert = alert(null);

        StepVerifier.create(
                disabled.publish(alert, EVENT_ID, WORKSPACE_ID, WORKSPACE_NAME, AlertEventType.TRACE_COST, TRIGGERED_AT,
                        payload(alert, List.of("event-1"), List.of())))
                .verifyComplete();

        assertThat(disabled.isEnabled()).isFalse();
        verifyNoInteractions(client);
    }

    private List<PutEventsRequestEntry> captureAllEntries(int calls) {
        var captor = ArgumentCaptor.forClass(PutEventsRequest.class);
        verify(client, times(calls)).putEvents(captor.capture());
        return captor.getAllValues().stream().map(request -> request.entries().getFirst()).toList();
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
