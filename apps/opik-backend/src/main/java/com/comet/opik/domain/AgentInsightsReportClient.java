package com.comet.opik.domain;

import com.google.inject.ImplementedBy;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound trigger for an Agent Insights report run. The real HTTP client that POSTs the trigger to
 * the Platform BE ({@code POST /agent-insights/generate}, report_type + period) is delivered by
 * OPIK-6854; this ticket (OPIK-6852) depends only on this interface and ships a {@link
 * NoopAgentInsightsReportClient} default so the jobs CRUD is complete and testable in isolation.
 */
@ImplementedBy(NoopAgentInsightsReportClient.class)
public interface AgentInsightsReportClient {

    boolean isEnabled();

    void triggerAgentInsights(String reportId, UUID projectId, String projectName,
            String workspaceName, Instant periodStart, Instant periodEnd);
}
