package com.comet.opik.domain;

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
    Mono<WorkspaceMetricsSummaryResponse> getWorkspaceFeedbackScoresSummary(WorkspaceMetricsSummaryRequest request);
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
}
