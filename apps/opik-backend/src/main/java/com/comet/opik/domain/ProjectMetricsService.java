package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

@ImplementedBy(ProjectMetricsServiceImpl.class)
public interface ProjectMetricsService {
    ProjectMetricResponse getProjectMetrics(UUID projectId, ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsServiceImpl implements ProjectMetricsService {
    @NonNull private final ProjectMetricsDAO projectMetricsDAO;

    public static final String NAME_TRACES = "traces";

    @Override
    public ProjectMetricResponse<? extends Number> getProjectMetrics(UUID projectId, ProjectMetricRequest request) {
        var dataPoints = projectMetricsDAO.getTraceCount(projectId,
                ProjectMetricsDAO.MetricsCriteria.builder()
                        .startTimestamp(request.startTimestamp())
                        .endTimestamp(request.endTimestamp())
                        .aggregation(request.aggregation())
                        .interval(request.interval())
                        .build()).block();
        var results = ProjectMetricResponse.Results.<Integer>builder()
                .name(NAME_TRACES)
                .timestamps(dataPoints.stream().map(DataPoint::time).toList())
                .values(dataPoints.stream().map(DataPoint::value).toList())
                .build();

        return ProjectMetricResponse.<Integer>builder()
                .projectId(projectId)
                .metricType(request.metricType())
                .interval(request.interval())
                .traces(List.of(results))
                .build();
    }
}
