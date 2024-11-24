package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
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
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@ImplementedBy(ProjectMetricsServiceImpl.class)
public interface ProjectMetricsService {
    String ERR_START_BEFORE_END = "'start_time' must be before 'end_time'";
    String ERR_PROJECT_METRIC_NOT_SUPPORTED = "metric '%s' is not supported";

    Mono<ProjectMetricResponse> getProjectMetrics(UUID projectId, ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsServiceImpl implements ProjectMetricsService {
    private final @NonNull ProjectMetricsDAO projectMetricsDAO;

    @Override
    public Mono<ProjectMetricResponse> getProjectMetrics(UUID projectId, ProjectMetricRequest request) {
        validate(request);

        return handlerFactory(request).apply(projectId, request)
                .map(dataPoints -> ProjectMetricResponse.builder()
                        .projectId(projectId)
                        .metricType(request.metricType())
                        .interval(request.interval())
                        .results(entriesToResults(dataPoints))
                        .build());
    }

    private void validate(ProjectMetricRequest request) {
        if (!request.intervalStart().isBefore(request.intervalEnd())) {
            throw new BadRequestException(ERR_START_BEFORE_END);
        }
    }

    private List<ProjectMetricResponse.Results> entriesToResults(List<ProjectMetricsDAO.Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }

        return entries.stream()
                .collect(Collectors.groupingBy(
                        ProjectMetricsDAO.Entry::name,
                        Collectors.mapping(
                                entry -> DataPoint.builder()
                                        .time(entry.time())
                                        .value(entry.value())
                                        .build(),
                                Collectors.toList()
                        )
                ))
                .entrySet().stream().map(entry -> ProjectMetricResponse.Results.builder()
                        .name(entry.getKey())
                        .data(entry.getValue())
                        .build()).toList();
    }

    private BiFunction<UUID, ProjectMetricRequest, Mono<List<ProjectMetricsDAO.Entry>>> handlerFactory(
            ProjectMetricRequest request) {
        if (request.metricType() == MetricType.TRACE_COUNT) {
            return projectMetricsDAO::getTraceCount;
        }

        if (request.metricType() == MetricType.FEEDBACK_SCORES) {
            return projectMetricsDAO::getFeedbackScores;
        }

        throw new BadRequestException(ERR_PROJECT_METRIC_NOT_SUPPORTED.formatted(request.metricType()));
    }
}
