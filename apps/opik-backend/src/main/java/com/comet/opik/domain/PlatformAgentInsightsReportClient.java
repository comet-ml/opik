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
    public void triggerAgentInsights(@NonNull Trigger trigger) {
        String reportId = trigger.reportId();
        UUID projectId = trigger.projectId();

        var payload = AgentInsightsTriggerRequest.builder()
                .reportType(REPORT_TYPE)
                .reportId(reportId)
                .projectId(projectId)
                .workspaceId(trigger.workspaceId())
                .periodStart(trigger.periodStart())
                .periodEnd(trigger.periodEnd())
                .triggerSource(trigger.triggerSource())
                .customPrompt(trigger.guidance())
                .build();

        try (Response response = httpClient.target(config.getTriggerUrl())
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(payload))) {
            if (response.getStatus() == Response.Status.PAYMENT_REQUIRED.getStatusCode()) {
                // An unreadable body is still a credits rejection: reading it as free_pool_exhausted instead would
                // cancel the whole rollout over a malformed response.
                String reason = AgentInsightsJob.FailureReason.FREE_POOL_EXHAUSTED.equals(readErrorCode(response))
                        ? AgentInsightsJob.FailureReason.FREE_POOL_EXHAUSTED
                        : AgentInsightsJob.FailureReason.OUT_OF_CREDITS;
                throw new AgentInsightsTriggerException(reason,
                        "Agent Insights trigger rejected for report '%s': insufficient credits"
                                .formatted(reportId));
            }
            if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()
                    && AgentInsightsJob.FailureReason.NO_API_KEY.equals(readErrorCode(response))) {
                throw new AgentInsightsTriggerException(AgentInsightsJob.FailureReason.NO_API_KEY,
                        ("Agent Insights trigger rejected for report '%s': no API key for the workspace owner or any "
                                + "organization admin").formatted(reportId));
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

    // Null when the body is missing or unreadable; callers decide what that means for their status.
    private String readErrorCode(Response response) {
        try {
            var body = response.readEntity(ErrorBody.class);
            return body == null ? null : body.errorCode();
        } catch (ProcessingException | IllegalStateException e) {
            log.warn("Could not read the error code off an Agent Insights {}", response.getStatus(), e);
            return null;
        }
    }

    // The platform's error body. Only the error code decides anything here.
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ErrorBody(String error, String errorCode) {
    }
}
