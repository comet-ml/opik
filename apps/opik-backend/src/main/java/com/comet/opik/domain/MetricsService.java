package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@ImplementedBy(MetricsServiceImpl.class)
public interface MetricsService {
    String ERR_START_BEFORE_END = "'start_time' must be before 'end_time'";

    Mono<ProjectMetricResponse<Number>> getProjectMetrics(UUID projectId, ProjectMetricRequest request);

    Mono<WorkspaceMetricsSummaryResponse> getWorkspaceFeedbackScoresSummary(WorkspaceMetricsSummaryRequest request);
}

@Slf4j
@Singleton
class MetricsServiceImpl implements MetricsService {
    private final @NonNull Map<MetricType, BiFunction<UUID, ProjectMetricRequest, Mono<List<ProjectMetricsDAO.Entry>>>> projectMetricHandler;
    private final @NonNull ProjectService projectService;
    private final @NonNull WorkspaceMetricsDAO workspaceMetricsDAO;

    @Inject
    public MetricsServiceImpl(@NonNull ProjectMetricsDAO projectMetricsDAO,
            @NonNull WorkspaceMetricsDAO workspaceMetricsDAO,
            @NonNull ProjectService projectService) {
        projectMetricHandler = Map.of(
                MetricType.TRACE_COUNT, projectMetricsDAO::getTraceCount,
                MetricType.FEEDBACK_SCORES, projectMetricsDAO::getFeedbackScores,
                MetricType.TOKEN_USAGE, projectMetricsDAO::getTokenUsage,
                MetricType.COST, projectMetricsDAO::getCost,
                MetricType.DURATION, projectMetricsDAO::getDuration,
                MetricType.GUARDRAILS_FAILED_COUNT, projectMetricsDAO::getGuardrailsFailedCount);
        this.projectService = projectService;
        this.workspaceMetricsDAO = workspaceMetricsDAO;
    }

    @Override
    public Mono<ProjectMetricResponse<Number>> getProjectMetrics(UUID projectId, ProjectMetricRequest request) {
        // Will throw an error in case we try to get a private project with public visibility
        projectService.get(projectId);
        return getMetricHandler(request.metricType())
                .apply(projectId, request)
                .map(dataPoints -> ProjectMetricResponse.builder()
                        .projectId(projectId)
                        .metricType(request.metricType())
                        .interval(request.interval())
                        .results(entriesToResults(dataPoints))
                        .build());
    }

    @Override
    public Mono<WorkspaceMetricsSummaryResponse> getWorkspaceFeedbackScoresSummary(
            WorkspaceMetricsSummaryRequest request) {
        return workspaceMetricsDAO.getFeedbackScoresSummary(request)
                .map(metrics -> WorkspaceMetricsSummaryResponse.builder()
                        .results(metrics)
                        .build());

    }

    private List<ProjectMetricResponse.Results<Number>> entriesToResults(List<ProjectMetricsDAO.Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }

        return entries.stream()
                // transform into map: name -> data points list
                .collect(Collectors.groupingBy(
                        ProjectMetricsDAO.Entry::name,
                        Collectors.mapping(
                                entry -> DataPoint.builder()
                                        .time(entry.time())
                                        .value(entry.value())
                                        .build(),
                                Collectors.toList())))
                // transform into a list of results
                .entrySet().stream().map(entry -> ProjectMetricResponse.Results.builder()
                        .name(entry.getKey())
                        .data(entry.getValue())
                        .build())
                .toList();
    }

    private BiFunction<UUID, ProjectMetricRequest, Mono<List<ProjectMetricsDAO.Entry>>> getMetricHandler(
            MetricType metricType) {
        return projectMetricHandler.get(metricType);
    }
}
