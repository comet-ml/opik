package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Platform Agent Insights Report Client")
class PlatformAgentInsightsReportClientTest {

    private static final String TRIGGER_PATH = "/opik/ollie/generate-agent-insights";

    private WireMockUtils.WireMockRuntime wireMock;
    private Client httpClient;
    private PlatformAgentInsightsReportClient client;

    @BeforeAll
    void setUp() {
        wireMock = WireMockUtils.startWireMock();
        // Production injects the Dropwizard-configured client; register the app mapper so the payload
        // serializes the same way here (a bare client has no JSR-310 support and fails on the period bounds).
        httpClient = ClientBuilder.newClient().register(new JacksonJsonProvider(JsonUtils.getMapper()));

        var config = new AgentInsightsReportConfig();
        config.setTriggerUrl(wireMock.runtimeInfo().getHttpBaseUrl() + TRIGGER_PATH);
        client = new PlatformAgentInsightsReportClient(httpClient, config);
    }

    @AfterAll
    void tearDown() {
        httpClient.close();
        wireMock.server().stop();
    }

    private void stubTriggerStatus(int status) {
        wireMock.server().stubFor(post(urlPathEqualTo(TRIGGER_PATH)).willReturn(aResponse().withStatus(status)));
    }

    private void trigger() {
        client.triggerAgentInsights(UUID.randomUUID().toString(), UUID.randomUUID(), "workspace-id",
                Instant.now().minusSeconds(86_400), Instant.now(), "manual");
    }

    @Test
    @DisplayName("A 402 rejection is named out of credits, so the run is not recorded as never started")
    void triggerAgentInsights__paymentRequired__namesOutOfCredits() {
        stubTriggerStatus(402);

        assertThatExceptionOfType(AgentInsightsTriggerException.class)
                .isThrownBy(this::trigger)
                .extracting(AgentInsightsTriggerException::getReason)
                .isEqualTo(AgentInsightsJob.FailureReason.OUT_OF_CREDITS);
    }

    @Test
    @DisplayName("Any other non-2xx is named as a run that did not start")
    void triggerAgentInsights__otherError__namesDidNotStart() {
        stubTriggerStatus(500);

        // Pins the reason, not just the exception type: both failures share one type now, so asserting the
        // type alone would let a server error be reported to the user as a billing problem.
        assertThatExceptionOfType(AgentInsightsTriggerException.class)
                .isThrownBy(this::trigger)
                .extracting(AgentInsightsTriggerException::getReason)
                .isEqualTo(AgentInsightsJob.FailureReason.DID_NOT_START);
    }

    @Test
    @DisplayName("An accepted trigger does not throw")
    void triggerAgentInsights__accepted__doesNotThrow() {
        stubTriggerStatus(202);

        assertThatNoException().isThrownBy(this::trigger);
    }
}
