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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

@ImplementedBy(ProjectMetricsServiceImpl.class)
public interface ProjectMetricsService {
    String ERR_START_BEFORE_END = "'start_time' must be before 'end_time'";
    String ERR_PROJECT_METRIC_NOT_SUPPORTED = "metric '%s' is not supported";

    Mono<ProjectMetricResponse<Number>> getProjectMetrics(UUID projectId, ProjectMetricRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectMetricsServiceImpl implements ProjectMetricsService {
    private final @NonNull ProjectMetricsDAO projectMetricsDAO;

    @Override
    public Mono<ProjectMetricResponse<Number>> getProjectMetrics(UUID projectId, ProjectMetricRequest request) {
        return getMetricHandler(request.metricType())
                .orElseThrow(
                        () -> new BadRequestException(ERR_PROJECT_METRIC_NOT_SUPPORTED.formatted(request.metricType())))
                .apply(projectId, request)
                .map(dataPoints -> ProjectMetricResponse.builder()
                        .projectId(projectId)
                        .metricType(request.metricType())
                        .interval(request.interval())
                        .results(entriesToResults(dataPoints))
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

    private Optional<BiFunction<UUID, ProjectMetricRequest, Mono<List<ProjectMetricsDAO.Entry>>>> getMetricHandler(
            MetricType metricType) {
        Map<MetricType, BiFunction<UUID, ProjectMetricRequest, Mono<List<ProjectMetricsDAO.Entry>>>> HANDLER_BY_TYPE = Map
                .of(
                        MetricType.TRACE_COUNT, projectMetricsDAO::getTraceCount,
                        MetricType.FEEDBACK_SCORES, projectMetricsDAO::getFeedbackScores,
                        MetricType.TOKEN_USAGE, projectMetricsDAO::getTokenUsage);

        return Optional.ofNullable(HANDLER_BY_TYPE.get(metricType));
    }
}
