package com.comet.opik.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload for the Platform BE Ollie trigger endpoint ({@code POST /opik/ollie/generate-agent-insights}).
 * Mirrors the platform's {@code AgentInsightsGenerateRequest} contract (snake_case); all fields required.
 */
@Builder(toBuilder = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record AgentInsightsTriggerRequest(
        @NonNull String reportType,
        @NonNull String reportId,
        @NonNull UUID projectId,
        @NonNull String workspaceId,
        @NonNull Instant periodStart,
        @NonNull Instant periodEnd,
        // "manual" (Run diagnostics) or "scheduled" (daily sweep) — forwarded so Ollie can tag its BI events.
        @NonNull String triggerSource) {
}
