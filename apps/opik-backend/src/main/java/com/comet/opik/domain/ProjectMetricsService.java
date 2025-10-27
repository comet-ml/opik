package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
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

    @Inject
    public ProjectMetricsServiceImpl(@NonNull ProjectMetricsDAO projectMetricsDAO,
            @NonNull ProjectService projectService) {
        projectMetricHandler = Map.ofEntries(
                // Existing metrics
                Map.entry(MetricType.TRACE_COUNT, projectMetricsDAO::getTraceCount),
                Map.entry(MetricType.THREAD_COUNT, projectMetricsDAO::getThreadCount),
                Map.entry(MetricType.THREAD_DURATION, projectMetricsDAO::getThreadDuration),
                Map.entry(MetricType.FEEDBACK_SCORES, projectMetricsDAO::getFeedbackScores),
                Map.entry(MetricType.THREAD_FEEDBACK_SCORES, projectMetricsDAO::getThreadFeedbackScores),
                Map.entry(MetricType.TOKEN_USAGE, projectMetricsDAO::getTokenUsage),
                Map.entry(MetricType.COST, projectMetricsDAO::getCost),
                Map.entry(MetricType.DURATION, projectMetricsDAO::getDuration),
                Map.entry(MetricType.GUARDRAILS_FAILED_COUNT, projectMetricsDAO::getGuardrailsFailedCount),

                // Easy additions
                Map.entry(MetricType.ERROR_COUNT, projectMetricsDAO::getErrorCount),
                Map.entry(MetricType.SPAN_COUNT, projectMetricsDAO::getSpanCount),
                Map.entry(MetricType.LLM_SPAN_COUNT, projectMetricsDAO::getLlmSpanCount),

                // Medium additions - token metrics
                Map.entry(MetricType.COMPLETION_TOKENS, projectMetricsDAO::getCompletionTokens),
                Map.entry(MetricType.PROMPT_TOKENS, projectMetricsDAO::getPromptTokens),
                Map.entry(MetricType.TOTAL_TOKENS, projectMetricsDAO::getTotalTokens),

                // Medium additions - count metrics
                Map.entry(MetricType.INPUT_COUNT, projectMetricsDAO::getInputCount),
                Map.entry(MetricType.OUTPUT_COUNT, projectMetricsDAO::getOutputCount),
                Map.entry(MetricType.METADATA_COUNT, projectMetricsDAO::getMetadataCount),
                Map.entry(MetricType.TAGS_AVERAGE, projectMetricsDAO::getTagsAverage),

                // Medium additions - calculated metrics
                Map.entry(MetricType.TRACE_WITH_ERRORS_PERCENT, projectMetricsDAO::getTraceWithErrorsPercent),
                Map.entry(MetricType.GUARDRAILS_PASS_RATE, projectMetricsDAO::getGuardrailsPassRate),
                Map.entry(MetricType.AVG_COST_PER_TRACE, projectMetricsDAO::getAvgCostPerTrace),

                // Medium additions - span duration
                Map.entry(MetricType.SPAN_DURATION, projectMetricsDAO::getSpanDuration));
        this.projectService = projectService;
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
