package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.UUID;

/**
 * Real {@link AgentInsightsReportClient}: POSTs a trigger to the Platform BE Ollie endpoint
 * ({@code POST /opik/ollie/generate-agent-insights}, OPIK-6854). The call is synchronous and throws on a
 * non-2xx response; the subscriber treats a failure as a dropped run (at-most-once), it does not retry.
 */
@Slf4j
@Singleton
public class PlatformAgentInsightsReportClient implements AgentInsightsReportClient {

    private static final String REPORT_TYPE = "agent_insights";

    private final @NonNull Client httpClient;
    private final @NonNull AgentInsightsReportConfig config;

    @Inject
    public PlatformAgentInsightsReportClient(@NonNull Client httpClient,
            @NonNull @Config("agentInsightsReport") AgentInsightsReportConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    @Override
    public void triggerAgentInsights(@NonNull String reportId, @NonNull UUID projectId,
            @NonNull String workspaceId, @NonNull Instant periodStart, @NonNull Instant periodEnd,
            @NonNull String triggerSource) {

        var payload = AgentInsightsTriggerRequest.builder()
                .reportType(REPORT_TYPE)
                .reportId(reportId)
                .projectId(projectId)
                .workspaceId(workspaceId)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .triggerSource(triggerSource)
                .build();

        try (Response response = httpClient.target(config.getTriggerUrl())
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(payload))) {
            if (response.getStatus() == Response.Status.PAYMENT_REQUIRED.getStatusCode()) {
                throw new AgentInsightsTriggerException(readPaymentRequiredReason(response),
                        "Agent Insights trigger rejected for report '%s': insufficient credits"
                                .formatted(reportId));
            }
            if (response.getStatus() >= 300) {
                // Signal failure to the subscriber, which logs and drops the run (at-most-once).
                throw new AgentInsightsTriggerException(AgentInsightsJob.FailureReason.DID_NOT_START,
                        "Agent Insights trigger returned %d for report '%s'".formatted(response.getStatus(),
                                reportId));
            }
            log.info("Agent Insights trigger accepted for report '{}', project '{}'", reportId, projectId);
        }
    }

    private String readPaymentRequiredReason(Response response) {
        try {
            // A 402 with no body reads as null, and counts as a plain credits rejection.
            var body = response.readEntity(PaymentRequiredBody.class);
            return body != null && AgentInsightsJob.FailureReason.FREE_POOL_EXHAUSTED.equals(body.errorCode())
                    ? AgentInsightsJob.FailureReason.FREE_POOL_EXHAUSTED
                    : AgentInsightsJob.FailureReason.OUT_OF_CREDITS;
        } catch (ProcessingException | IllegalStateException e) {
            // An unreadable body is still a credits rejection: reading it as free_pool_exhausted instead would
            // cancel the whole rollout over a malformed response.
            log.warn("Could not read the error code off an Agent Insights 402", e);
            return AgentInsightsJob.FailureReason.OUT_OF_CREDITS;
        }
    }

    // The platform's 402 body. Only the error code decides anything here.
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record PaymentRequiredBody(String error, String errorCode) {
    }
}
