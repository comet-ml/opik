package com.comet.opik.domain;

import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@ImplementedBy(WorkspaceMetricsServiceImpl.class)
public interface WorkspaceMetricsService {
    @Deprecated
    Mono<WorkspaceMetricsSummaryResponse> getWorkspaceFeedbackScoresSummary(WorkspaceMetricsSummaryRequest request);

    @Deprecated
    Mono<WorkspaceMetricResponse> getWorkspaceFeedbackScores(WorkspaceMetricRequest request);

    Mono<WorkspaceMetricsSummaryResponse.Result> getWorkspaceCostsSummary(WorkspaceMetricsSummaryRequest request);

    Mono<WorkspaceMetricResponse> getWorkspaceCosts(WorkspaceMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
class WorkspaceMetricsServiceImpl implements WorkspaceMetricsService {
    private final @NonNull WorkspaceMetricsDAO workspaceMetricsDAO;

    @Override
    public Mono<WorkspaceMetricsSummaryResponse> getWorkspaceFeedbackScoresSummary(
            @NonNull WorkspaceMetricsSummaryRequest request) {
        return workspaceMetricsDAO.getFeedbackScoresSummary(request)
                .map(metrics -> WorkspaceMetricsSummaryResponse.builder()
                        .results(metrics)
                        .build());

    }

    @Override
    public Mono<WorkspaceMetricResponse> getWorkspaceFeedbackScores(@NonNull WorkspaceMetricRequest request) {
        return workspaceMetricsDAO.getFeedbackScoresDaily(request)
                .map(results -> WorkspaceMetricResponse.builder()
                        .results(results)
                        .build());
    }

    @Override
    public Mono<WorkspaceMetricsSummaryResponse.Result> getWorkspaceCostsSummary(
            @NonNull WorkspaceMetricsSummaryRequest request) {
        return workspaceMetricsDAO.getCostsSummary(request);
    }

    @Override
    public Mono<WorkspaceMetricResponse> getWorkspaceCosts(@NonNull WorkspaceMetricRequest request) {
        return workspaceMetricsDAO.getCostsDaily(request)
                .map(results -> WorkspaceMetricResponse.builder()
                        .results(results)
                        .build());
    }
}
