package com.comet.opik.infrastructure;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.events.webhooks.MetricsAlertPayload;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.resources.utils.resources.AlertResourceClient;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.tomakehurst.wiremock.WireMockServer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.awaitility.Awaitility;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * Reads the alert total cost the only way it is read: the alert job is its sole reader, so a cost alert is created
 * with a zero threshold, which fires on the job's first run for any cost, and the webhook carries the total read.
 */
@RequiredArgsConstructor
class TraceCostAlerts {

    /** The alert's window: the total covers the spans of the traces whose ids fall within it before the job runs. */
    static final Duration WINDOW = Duration.ofHours(1);

    private final @NonNull WireMockServer wireMock;
    private final @NonNull AlertResourceClient alertResourceClient;
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    /**
     * The total for the project's traces in the window, or for all the workspace's when {@code projectId} is null.
     */
    BigDecimal totalCost(UUID projectId, @NonNull String apiKey, @NonNull String workspaceName,
            @NonNull String workspaceId) {
        var webhookPath = "/alert-webhook-" + RandomStringUtils.secure().nextAlphanumeric(32);
        wireMock.stubFor(post(urlEqualTo(webhookPath)).willReturn(aResponse().withStatus(200)));
        // The webhook sender names the workspace in the payload.
        wireMock.stubFor(get(urlPathEqualTo("/workspaces/workspace-name"))
                .withQueryParam("id", equalTo(workspaceId))
                .willReturn(aResponse().withStatus(200).withBody(workspaceName)));
        var alert = podamFactory.manufacturePojo(Alert.class);
        alert = alert.toBuilder()
                .enabled(true)
                .alertType(AlertType.GENERAL)
                .projectId(projectId)
                .webhook(alert.webhook().toBuilder()
                        .url("http://localhost:%d%s".formatted(wireMock.port(), webhookPath))
                        .secretToken(UUID.randomUUID().toString())
                        .createdBy(null)
                        .createdAt(null)
                        .build())
                .triggers(List.of(AlertTrigger.builder()
                        .eventType(AlertEventType.TRACE_COST)
                        .triggerConfigs(List.of(AlertTriggerConfig.builder()
                                .type(AlertTriggerConfigType.THRESHOLD_COST)
                                .configValue(Map.of(
                                        AlertTriggerConfig.THRESHOLD_CONFIG_KEY, "0",
                                        AlertTriggerConfig.WINDOW_CONFIG_KEY, String.valueOf(WINDOW.toSeconds())))
                                .build()))
                        .build()))
                .build();
        var alertId = alertResourceClient.createAlert(alert, apiKey, workspaceName, HttpStatus.SC_CREATED);

        String body;
        try {
            body = Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500))
                    .until(() -> wireMock.findAll(postRequestedFor(urlEqualTo(webhookPath))),
                            requests -> !requests.isEmpty())
                    .getFirst().getBodyAsString();
        } finally {
            // The scheduler re-fires an enabled alert on every run, so it must not outlive the caller.
            alertResourceClient.deleteAlertBatch(BatchDelete.builder().ids(Set.of(alertId)).build(), apiKey,
                    workspaceName, HttpStatus.SC_NO_CONTENT);
        }
        var event = JsonUtils.readValue(body, new TypeReference<WebhookEvent<Map<String, Object>>>() {
        });
        var metadata = (List<?>) event.getPayload().get("metadata");
        var payload = JsonUtils.readValue(JsonUtils.writeValueAsString(metadata.getFirst()),
                MetricsAlertPayload.class);
        return new BigDecimal(payload.metricValue());
    }
}
