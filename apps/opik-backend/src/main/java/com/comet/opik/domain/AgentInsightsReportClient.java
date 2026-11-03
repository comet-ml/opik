package com.comet.opik.domain;

import com.google.inject.ImplementedBy;
import lombok.Builder;
import lombok.NonNull;

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

    void triggerAgentInsights(Trigger trigger);

    /**
     * @param triggerSource "manual", "scheduled" or "auto_first_run", forwarded so Ollie can tag its BI events.
     * @param guidance      the project guidance, or null when there is none or guidance is not active.
     */
    @Builder(toBuilder = true)
    record Trigger(@NonNull String reportId, @NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull Instant periodStart, @NonNull Instant periodEnd, @NonNull String triggerSource,
            String guidance) {
    }
}
