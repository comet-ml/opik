package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
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

@ImplementedBy(ProjectMetricsServiceImpl.class)
public interface ProjectMetricsService {
    String ERR_START_BEFORE_END = "'start_time' must be before 'end_time'";

    Mono<ProjectMetricResponse<Number>> getProjectMetrics(UUID projectId, ProjectMetricRequest request);
}

@Slf4j
@Singleton
class ProjectMetricsServiceImpl implements ProjectMetricsService {
    private final @NonNull Map<MetricType, BiFunction<UUID, ProjectMetricRequest, Mono<List<ProjectMetricsDAO.Entry>>>> projectMetricHandler;
    private final @NonNull ProjectService projectService;
    private final @NonNull InstantToUUIDMapper instantToUUIDMapper;

    @Inject
    public ProjectMetricsServiceImpl(@NonNull ProjectMetricsDAO projectMetricsDAO,
            @NonNull ProjectService projectService,
            @NonNull InstantToUUIDMapper instantToUUIDMapper) {
        projectMetricHandler = Map.ofEntries(
                Map.entry(MetricType.TRACE_COUNT, projectMetricsDAO::getTraceCount),
                Map.entry(MetricType.THREAD_COUNT, projectMetricsDAO::getThreadCount),
                Map.entry(MetricType.THREAD_DURATION, projectMetricsDAO::getThreadDuration),
                Map.entry(MetricType.FEEDBACK_SCORES, projectMetricsDAO::getFeedbackScores),
                Map.entry(MetricType.THREAD_FEEDBACK_SCORES, projectMetricsDAO::getThreadFeedbackScores),
                Map.entry(MetricType.TOKEN_USAGE, projectMetricsDAO::getTokenUsage),
                Map.entry(MetricType.COST, projectMetricsDAO::getCost),
                Map.entry(MetricType.DURATION, projectMetricsDAO::getDuration),
                Map.entry(MetricType.GUARDRAILS_FAILED_COUNT, projectMetricsDAO::getGuardrailsFailedCount),
                Map.entry(MetricType.SPAN_FEEDBACK_SCORES, projectMetricsDAO::getSpanFeedbackScores),
                Map.entry(MetricType.SPAN_COUNT, projectMetricsDAO::getSpanCount),
                Map.entry(MetricType.SPAN_DURATION, projectMetricsDAO::getSpanDuration),
                Map.entry(MetricType.SPAN_TOKEN_USAGE, projectMetricsDAO::getSpanTokenUsage));
        this.projectService = projectService;
        this.instantToUUIDMapper = instantToUUIDMapper;
    }

    @Override
    public Mono<ProjectMetricResponse<Number>> getProjectMetrics(UUID projectId, ProjectMetricRequest request) {
        return validateProject(projectId)
                .then(Mono.defer(() -> Mono.just(request.toBuilder() // Enrich request with UUID bounds derived from time parameters for efficient ID-based filtering
                        .uuidFromTime(instantToUUIDMapper.toLowerBound(request.intervalStart()))
                        .uuidToTime(instantToUUIDMapper.toUpperBound(request.intervalEnd()))
                        .build())))
                .flatMap(enrichedRequest -> getMetricHandler(enrichedRequest.metricType())
                        .apply(projectId, enrichedRequest)
                        .map(dataPoints -> ProjectMetricResponse.builder()
                                .projectId(projectId)
                                .metricType(enrichedRequest.metricType())
                                .interval(enrichedRequest.interval())
                                .results(entriesToResults(dataPoints))
                                .build()));
    }

    private Mono<Void> validateProject(UUID projectId) {
        // Validates that the project exists and is accessible within the current workspace context
        return projectService.getOrFail(projectId).then();
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
