package com.comet.opik.api.spend;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;

/**
 * KPI summary for the AI Spend home cards. {@code results} carries the
 * count metrics (messages, active/total users) as current/previous scalars;
 * the spend tiers ship per model for the current and previous windows so the
 * FE prices each model at its own rate before computing total spend, the
 * per-user average, and the period-over-period trend.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpendSummaryResponse(
        List<WorkspaceMetricsSummaryResponse.Result> results,
        List<ModelTiers> spendCurrent,
        List<ModelTiers> spendPrevious) {
}
