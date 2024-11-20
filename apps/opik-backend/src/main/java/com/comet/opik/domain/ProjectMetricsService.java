package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@ImplementedBy(ProjectMetricsServiceImpl.class)
public interface ProjectMetricsService {
    public static final String ERR_START_BEFORE_END = "'start_time' must be before 'end_time'";

    Mono<ProjectMetricResponse> getProjectMetrics(UUID projectId, ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsServiceImpl implements ProjectMetricsService {
    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull ProjectMetricsDAO projectMetricsDAO;

    public static final String NAME_TRACES = "traces";

    @Override
    public Mono<ProjectMetricResponse> getProjectMetrics(UUID projectId, ProjectMetricRequest request) {
        var criteria = ProjectMetricsDAO.MetricsCriteria.builder()
                .startTimestamp(request.startTimestamp())
                .endTimestamp(request.endTimestamp())
                .aggregation(request.aggregation())
                .interval(request.interval())
                .build();

        return template.nonTransaction(connection -> projectMetricsDAO.getTraceCount(projectId, criteria,
                        connection)
                .map(dataPoints -> ProjectMetricResponse.<Integer>builder()
                        .projectId(projectId)
                        .metricType(request.metricType())
                        .interval(request.interval())
                        .traces(List.of(ProjectMetricResponse.Results.<Integer>builder()
                                .name(NAME_TRACES)
                                .timestamps(dataPoints.stream().map(DataPoint::time).toList())
                                .values(dataPoints.stream().map(DataPoint::value).toList())
                                .build()))
                        .build()));
    }
}
