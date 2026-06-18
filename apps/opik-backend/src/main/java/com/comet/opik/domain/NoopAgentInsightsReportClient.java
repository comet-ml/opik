package com.comet.opik.domain;

import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

/** Default no-op binding until OPIK-6854 wires the real Platform-BE client ({@code isEnabled()} is false). */
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
        log.info("Agent Insights trigger disabled; skipping immediate run for project '{}'", projectId);
    }
}
