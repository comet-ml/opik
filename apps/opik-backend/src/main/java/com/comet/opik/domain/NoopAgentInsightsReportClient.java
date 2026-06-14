package com.comet.opik.domain;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

/**
 * Default {@link AgentInsightsReportClient} binding: no-op until OPIK-6854 wires the real Platform-BE
 * client. Mirrors {@code OrchestratorClient.isEnabled() == false} — {@code isEnabled()} returns false
 * so callers skip the immediate run rather than failing.
 */
@Singleton
@Slf4j
public class NoopAgentInsightsReportClient implements AgentInsightsReportClient {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void triggerAgentInsights(String reportId, UUID projectId, String projectName,
            String workspaceName, Instant periodStart, Instant periodEnd) {
        // Defensive: callers guard on isEnabled() (false here), so this is normally unreachable.
        log.info("Agent Insights trigger disabled; skipping immediate run for project '{}'", projectId);
    }
}
