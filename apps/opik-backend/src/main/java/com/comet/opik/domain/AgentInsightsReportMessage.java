package com.comet.opik.domain;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.util.UUID;

/**
 * A queued request to generate an Agent Insights report for a (workspace, project) over a time window.
 * Published by {@link AgentInsightsReportPublisher} and consumed by the Agent Insights report subscriber,
 * which performs the actual (bounded) trigger via {@link AgentInsightsReportClient}.
 */
@Builder(toBuilder = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public record AgentInsightsReportMessage(
        @NonNull String reportId,
        @NonNull UUID projectId,
        @NonNull String workspaceId,
        @NonNull Instant periodStart,
        @NonNull Instant periodEnd) {
}
