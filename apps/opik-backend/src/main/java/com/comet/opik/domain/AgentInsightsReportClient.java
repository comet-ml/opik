package com.comet.opik.domain;

import com.google.inject.ImplementedBy;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound trigger for an Agent Insights report run. The default {@link PlatformAgentInsightsReportClient}
 * POSTs the trigger to the Platform BE ({@code POST /opik/ollie/generate-agent-insights}, OPIK-6854). The
 * trigger URL has a config default and the feature is gated by the Agent Insights toggle, so the client is
 * always ready when invoked. Tests bind a recording stub, overriding this default.
 */
@ImplementedBy(PlatformAgentInsightsReportClient.class)
public interface AgentInsightsReportClient {

    void triggerAgentInsights(String reportId, UUID projectId, String workspaceId,
            Instant periodStart, Instant periodEnd, String triggerSource);
}
