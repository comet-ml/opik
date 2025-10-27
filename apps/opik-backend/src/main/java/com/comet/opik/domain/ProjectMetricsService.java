package com.comet.opik.domain;

import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

@ImplementedBy(ProjectMetricsServiceImpl.class)
public interface ProjectMetricsService {
    String ERR_START_BEFORE_END = "'start_time' must be before 'end_time'";

    // Legacy constants for backward compatibility with tests
    String NAME_TRACES = "traces";
    String NAME_THREADS = "threads";
    String NAME_COST = "cost";
    String NAME_GUARDRAILS_FAILED_COUNT = "failed";

    String TRACE_DURATION_PREFIX = "duration";
    String THREAD_DURATION_PREFIX = "thread_duration";
    String P50 = "p50";
    String P90 = "p90";
    String P99 = "p99";
    String NAME_TRACE_DURATION_P50 = String.join(".", TRACE_DURATION_PREFIX, P50);
    String NAME_TRACE_DURATION_P90 = String.join(".", TRACE_DURATION_PREFIX, P90);
    String NAME_TRACE_DURATION_P99 = String.join(".", TRACE_DURATION_PREFIX, P99);
    String NAME_THREAD_DURATION_P50 = String.join(".", THREAD_DURATION_PREFIX, P50);
    String NAME_THREAD_DURATION_P90 = String.join(".", THREAD_DURATION_PREFIX, P90);
    String NAME_THREAD_DURATION_P99 = String.join(".", THREAD_DURATION_PREFIX, P99);

    Mono<ProjectMetricResponse<Number>> getProjectMetrics(UUID projectId, ProjectMetricRequest request);
}

@Slf4j
@Singleton
class ProjectMetricsServiceImpl implements ProjectMetricsService {
    private final @NonNull ProjectService projectService;
    private final @NonNull ProjectMetricsAggregatorService aggregatorService;

    @Inject
    public ProjectMetricsServiceImpl(@NonNull ProjectService projectService,
            @NonNull ProjectMetricsAggregatorService aggregatorService) {
        this.projectService = projectService;
        this.aggregatorService = aggregatorService;
    }

    @Override
    public Mono<ProjectMetricResponse<Number>> getProjectMetrics(UUID projectId, ProjectMetricRequest request) {
        // Will throw an error in case we try to get a private project with public visibility
        projectService.get(projectId);

        log.debug("Using ProjectMetricsAggregatorService (reusing existing stats queries) for metric '{}'",
                request.metricType());

        @SuppressWarnings("unchecked")
        Mono<ProjectMetricResponse<Number>> result = aggregatorService
                .getProjectMetrics(projectId, request)
                .map(response -> (ProjectMetricResponse<Number>) response);

        return result;
    }
}
