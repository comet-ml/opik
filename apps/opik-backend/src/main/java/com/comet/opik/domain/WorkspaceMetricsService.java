package com.comet.opik.domain;

import com.comet.opik.api.metrics.BreakdownQueryBuilder;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.metrics.WorkspaceSpanMetricRequest;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;

@ImplementedBy(WorkspaceMetricsServiceImpl.class)
public interface WorkspaceMetricsService {
    @Deprecated
    Mono<WorkspaceMetricsSummaryResponse> getWorkspaceFeedbackScoresSummary(WorkspaceMetricsSummaryRequest request);

    @Deprecated
    Mono<WorkspaceMetricResponse> getWorkspaceFeedbackScores(WorkspaceMetricRequest request);

    Mono<WorkspaceMetricsSummaryResponse.Result> getWorkspaceCostsSummary(WorkspaceMetricsSummaryRequest request);

    Mono<WorkspaceMetricResponse> getWorkspaceCosts(WorkspaceMetricRequest request);

    Mono<WorkspaceMetricResponse> getWorkspaceSpanMetric(WorkspaceSpanMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
class WorkspaceMetricsServiceImpl implements WorkspaceMetricsService {
    private final @NonNull WorkspaceMetricsDAO workspaceMetricsDAO;
    private final @NonNull ProjectService projectService;

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

    @Override
    public Mono<WorkspaceMetricResponse> getWorkspaceSpanMetric(@NonNull WorkspaceSpanMetricRequest request) {
        validate(request);
        return resolveProjectIds(request)
                .flatMap(resolved -> CollectionUtils.isEmpty(resolved.projectIds())
                        ? Mono.just(List.<WorkspaceMetricResponse.Result>of())
                        : dispatch(resolved))
                .map(results -> WorkspaceMetricResponse.builder()
                        .results(results)
                        .build());
    }

    // "All projects" (empty projectIds) is resolved into the explicit set of workspace project ids so the DAO always
    // queries a bounded `project_id IN (...)` list. This prunes well on the spans primary key for small and moderate
    // selections, but it is only bounded to the workspace's projects: for a tenant with many projects, IN(<all ids>)
    // reads roughly the same granules as a full workspace scan, since the id/time window can't prune at the primary-key
    // level across many disjoint project prefixes. An explicit selection passes through unchanged; a workspace with no
    // projects yields nothing.
    private Mono<WorkspaceSpanMetricRequest> resolveProjectIds(WorkspaceSpanMetricRequest request) {
        if (CollectionUtils.isNotEmpty(request.projectIds())) {
            return Mono.just(request);
        }
        return projectService.findProjectIdsByWorkspace()
                .map(projectIds -> request.toBuilder().projectIds(projectIds).build());
    }

    private Mono<List<WorkspaceMetricResponse.Result>> dispatch(WorkspaceSpanMetricRequest request) {
        return switch (request.metricType()) {
            case SPAN_TOKEN_USAGE -> workspaceMetricsDAO.getSpanTokenUsage(request);
            default -> throw new BadRequestException("Unsupported metric type '%s'".formatted(request.metricType()));
        };
    }

    private void validate(WorkspaceSpanMetricRequest request) {
        if (request.metricType() == null) {
            throw new BadRequestException("'metric_type' must be provided");
        }
        if (request.interval() == null) {
            throw new BadRequestException("'interval' must be provided");
        }
        if (!WorkspaceMetricsDAO.SUPPORTED_SPAN_METRICS.contains(request.metricType())) {
            throw new BadRequestException("Unsupported metric type '%s'. Supported: %s"
                    .formatted(request.metricType(), WorkspaceMetricsDAO.SUPPORTED_SPAN_METRICS));
        }
        if (request.hasBreakdown()) {
            try {
                BreakdownQueryBuilder.validate(request.breakdown(), request.metricType());
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException(exception.getMessage());
            }
            if (request.metricType() == MetricType.SPAN_TOKEN_USAGE
                    && StringUtils.isBlank(request.breakdown().subMetric())) {
                throw new BadRequestException(
                        "'sub_metric' is required for token usage breakdown. It should be the usage key name (e.g., completion_tokens, prompt_tokens).");
            }
        }
    }
}
