package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@ImplementedBy(ProjectMetricsServiceImpl.class)
public interface ProjectMetricsService {
    String ERR_START_BEFORE_END = "'start_time' must be before 'end_time'";

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
        validate(request);

        return template.nonTransaction(connection -> projectMetricsDAO.getTraceCount(projectId, request,
                        connection)
                .map(dataPoints -> ProjectMetricResponse.builder()
                        .projectId(projectId)
                        .metricType(request.metricType())
                        .interval(request.interval())
                        .results(List.of(ProjectMetricResponse.Results.builder()
                                .name(NAME_TRACES)
                                .data(dataPoints)
                                .build()))
                        .build()));
    }

    private void validate(ProjectMetricRequest request) {
        if (!request.intervalStart().isBefore(request.intervalEnd())) {
            throw new BadRequestException(ERR_START_BEFORE_END);
        }
    }
}
