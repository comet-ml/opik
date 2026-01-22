package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.metrics.BreakdownConfig;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                .then(Mono.defer(() -> validateBreakdownConfig(request)))
                .then(Mono.defer(() -> Mono.just(request.toBuilder()
                        // Enrich request with UUID bounds derived from time parameters for efficient ID-based filtering
                        .uuidFromTime(instantToUUIDMapper.toLowerBound(request.intervalStart()))
                        .uuidToTime(instantToUUIDMapper.toUpperBound(request.intervalEnd()))
                        .build())))
                .flatMap(enrichedRequest -> getMetricHandler(enrichedRequest.metricType())
                        .apply(projectId, enrichedRequest)
                        .map(dataPoints -> buildResponse(projectId, enrichedRequest, dataPoints)));
    }

    private Mono<Void> validateProject(UUID projectId) {
        // Validates that the project exists and is accessible within the current workspace context
        return projectService.getOrFail(projectId).then();
    }

    private static final Set<MetricType> DURATION_METRICS = EnumSet.of(
            MetricType.DURATION,
            MetricType.SPAN_DURATION,
            MetricType.THREAD_DURATION);

    private static final Set<MetricType> FEEDBACK_SCORES_METRICS = EnumSet.of(
            MetricType.FEEDBACK_SCORES,
            MetricType.SPAN_FEEDBACK_SCORES,
            MetricType.THREAD_FEEDBACK_SCORES);

    private static final Set<MetricType> TOKEN_USAGE_METRICS = EnumSet.of(
            MetricType.TOKEN_USAGE,
            MetricType.SPAN_TOKEN_USAGE);

    private static final Set<String> VALID_DURATION_SUB_METRICS = Set.of("p50", "p90", "p99");

    private Mono<Void> validateBreakdownConfig(ProjectMetricRequest request) {
        if (request.hasBreakdown()) {
            try {
                request.breakdown().validate(request.metricType());

                String subMetric = request.breakdown().subMetric();
                // For duration metrics with breakdown, subMetric is required (p50, p90, p99)
                if (DURATION_METRICS.contains(request.metricType())) {
                    if (StringUtils.isBlank(subMetric)) {
                        return Mono.error(new BadRequestException(
                                "sub_metric is required for duration metrics with breakdown. Valid values: p50, p90, p99"));
                    }
                    if (!VALID_DURATION_SUB_METRICS.contains(subMetric.toLowerCase())) {
                        return Mono.error(new BadRequestException(
                                "Invalid sub_metric '%s'. Valid values: p50, p90, p99".formatted(subMetric)));
                    }
                }

                // For feedback scores metrics with breakdown, subMetric (feedback score name) is required
                if (FEEDBACK_SCORES_METRICS.contains(request.metricType())) {
                    if (StringUtils.isBlank(subMetric)) {
                        return Mono.error(new BadRequestException(
                                "sub_metric is required for feedback scores metrics with breakdown. It should be the feedback score name."));
                    }
                }

                // For token usage metrics with breakdown, subMetric (usage key name) is required
                if (TOKEN_USAGE_METRICS.contains(request.metricType())) {
                    if (StringUtils.isBlank(subMetric)) {
                        return Mono.error(new BadRequestException(
                                "sub_metric is required for token usage metrics with breakdown. It should be the usage key name (e.g., completion_tokens, prompt_tokens)."));
                    }
                }
            } catch (IllegalArgumentException e) {
                return Mono.error(new BadRequestException(e.getMessage()));
            }
        }
        return Mono.empty();
    }

    private ProjectMetricResponse<Number> buildResponse(UUID projectId, ProjectMetricRequest request,
            List<ProjectMetricsDAO.Entry> entries) {
        var builder = ProjectMetricResponse.<Number>builder()
                .projectId(projectId)
                .metricType(request.metricType())
                .interval(request.interval());

        if (request.hasBreakdown()) {
            builder.results(processBreakdownEntries(entries));
        } else {
            builder.results(entriesToResults(entries));
        }

        return builder.build();
    }

    private List<ProjectMetricResponse.Results<Number>> processBreakdownEntries(List<ProjectMetricsDAO.Entry> entries) {
        if (entries.isEmpty()) {
            return List.of();
        }

        // Group entries by their breakdown group name
        Map<String, List<ProjectMetricsDAO.Entry>> entriesByGroup = entries.stream()
                .collect(Collectors.groupingBy(
                        entry -> entry.groupName() != null ? entry.groupName() : BreakdownConfig.UNKNOWN_GROUP_NAME));

        // Calculate aggregate value for each group for sorting (always by value descending)
        Map<String, Double> groupAggregates = entriesByGroup.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .mapToDouble(entry -> entry.value() != null ? entry.value().doubleValue() : 0)
                                .sum()));

        // Sort groups by value descending (top groups first)
        List<String> sortedGroups = groupAggregates.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        // Apply fixed limit of 10 and always create "Others" group for remaining
        List<String> topGroups = sortedGroups.stream().limit(BreakdownConfig.LIMIT).toList();
        List<String> otherGroups = sortedGroups.stream().skip(BreakdownConfig.LIMIT).toList();

        // Build results for top groups
        Map<String, List<ProjectMetricsDAO.Entry>> resultGroups = new LinkedHashMap<>();
        for (String group : topGroups) {
            resultGroups.put(group, entriesByGroup.get(group));
        }

        // Add "Others" group if there are remaining groups beyond the limit
        if (!otherGroups.isEmpty()) {
            List<ProjectMetricsDAO.Entry> othersEntries = otherGroups.stream()
                    .flatMap(group -> entriesByGroup.get(group).stream())
                    .map(entry -> ProjectMetricsDAO.Entry.builder()
                            .name(entry.name())
                            .time(entry.time())
                            .value(entry.value())
                            .groupName(BreakdownConfig.OTHERS_GROUP_NAME)
                            .build())
                    .toList();
            resultGroups.put(BreakdownConfig.OTHERS_GROUP_NAME, othersEntries);
        }

        // Convert to response format - with breakdown, each group has exactly one metric type
        return resultGroups.entrySet().stream()
                .map(entry -> ProjectMetricResponse.Results.<Number>builder()
                        .name(entry.getKey())
                        .data(entry.getValue().stream()
                                .map(e -> DataPoint.<Number>builder()
                                        .time(e.time())
                                        .value(e.value())
                                        .build())
                                .toList())
                        .build())
                .toList();
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
                                entry -> DataPoint.<Number>builder()
                                        .time(entry.time())
                                        .value(entry.value())
                                        .build(),
                                Collectors.toList())))
                // transform into a list of results
                .entrySet().stream().map(entry -> ProjectMetricResponse.Results.<Number>builder()
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
