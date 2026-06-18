package com.comet.opik.domain;

import com.google.inject.ImplementedBy;

import java.time.Instant;
import java.util.UUID;

/** Outbound trigger for an Agent Insights report run; the real Platform-BE HTTP client is delivered by OPIK-6854. */
@ImplementedBy(NoopAgentInsightsReportClient.class)
public interface AgentInsightsReportClient {

    boolean isEnabled();

    void triggerAgentInsights(String reportId, UUID projectId, String projectName,
            String workspaceName, Instant periodStart, Instant periodEnd);
}
