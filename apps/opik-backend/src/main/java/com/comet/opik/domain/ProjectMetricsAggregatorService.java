package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Aggregator service that generates dashboard metrics by reusing existing stats queries.
 * <p>
 * This service orchestrates calls to TraceService, SpanService, etc. to fetch aggregated stats
 * for time buckets, then transforms them into time-series data for dashboard visualizations.
 * <p>
 * Benefits:
 * - Reuses battle-tested existing queries
 * - Maintains consistency with traces/spans pages
 * - Lower maintenance burden
 * - Can be easily rolled back via feature toggle
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ProjectMetricsAggregatorService {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ProjectService projectService;

    /**
     * Get project metrics by aggregating stats over time buckets.
     * <p>
     * Strategy:
     * 1. Generate time buckets based on interval (HOURLY/DAILY/WEEKLY)
     * 2. Call getStats() for each time bucket
     * 3. Transform stats into time-series data points
     * 4. Group by series name and return
     *
     * @param projectId Project ID
     * @param request   Metric request with type, interval, filters
     * @return Time-series metric response
     */
    @WithSpan
    public Mono<ProjectMetricResponse<? extends Number>> getProjectMetrics(
            @NonNull UUID projectId,
            @NonNull ProjectMetricRequest request) {

        log.info("Aggregating metrics for project '{}', metric '{}', interval '{}'",
                projectId, request.metricType(), request.interval());

        // Validate that we can handle this metric
        if (isUnsupportedMetric(request.metricType())) {
            return Mono.error(new UnsupportedOperationException(
                    "Metric '%s' not yet supported by aggregator service".formatted(request.metricType())));
        }

        // Generate time buckets
        List<TimeBucket> buckets = TimeBucketGenerator.generateBuckets(
                request.intervalStart(),
                request.intervalEnd(),
                request.interval());

        if (buckets.isEmpty()) {
            log.warn("No time buckets generated for request: '{}'", request);
            return Mono.just(ProjectMetricResponse.<Number>builder()
                    .projectId(projectId)
                    .metricType(request.metricType())
                    .interval(request.interval())
                    .results(List.of())
                    .build());
        }

        log.debug("Generated '{}' time buckets for metric aggregation", buckets.size());

        // Fetch stats for each bucket and transform to data points
        return Flux.fromIterable(buckets)
                .flatMap(bucket -> Mono.deferContextual(ctx -> {
                    // Propagate context to each bucket call
                    return getStatsForBucket(projectId, request, bucket);
                }))
                .collectList()
                .map(dataPointsList -> aggregateAndGroupDataPoints(projectId, request, dataPointsList));
    }

    /**
     * Fetch stats for a single time bucket and transform to data points.
     */
    private Mono<List<DataPoint<? extends Number>>> getStatsForBucket(
            UUID projectId,
            ProjectMetricRequest request,
            TimeBucket bucket) {

        // Determine if this metric needs span stats or trace stats
        boolean needsSpanStats = isSpanMetric(request.metricType());

        if (needsSpanStats) {
            return getSpanStatsForBucket(projectId, request, bucket);
        } else {
            return getTraceStatsForBucket(projectId, request, bucket);
        }
    }

    /**
     * Fetch trace stats for a single time bucket.
     */
    private Mono<List<DataPoint<? extends Number>>> getTraceStatsForBucket(
            UUID projectId,
            ProjectMetricRequest request,
            TimeBucket bucket) {

        // Build filters with time bucket boundaries
        List<com.comet.opik.api.filter.TraceFilter> filtersWithTime = new java.util.ArrayList<>();

        // Add user-provided filters if any
        if (request.traceFilters() != null) {
            filtersWithTime.addAll(request.traceFilters());
        }

        // Add time range filters for this bucket
        filtersWithTime.add(com.comet.opik.api.filter.TraceFilter.builder()
                .field(com.comet.opik.api.filter.TraceField.START_TIME)
                .operator(com.comet.opik.api.filter.Operator.GREATER_THAN_EQUAL)
                .value(bucket.start().toString())
                .build());

        filtersWithTime.add(com.comet.opik.api.filter.TraceFilter.builder()
                .field(com.comet.opik.api.filter.TraceField.START_TIME)
                .operator(com.comet.opik.api.filter.Operator.LESS_THAN)
                .value(bucket.end().toString())
                .build());

        // Build search criteria for this time bucket
        TraceSearchCriteria criteria = TraceSearchCriteria.builder()
                .projectId(projectId)
                .filters(filtersWithTime)
                .build();

        log.debug("Fetching trace stats for bucket '{}' to '{}'", bucket.start(), bucket.end());

        // Call existing trace stats endpoint with context propagation
        return Mono.deferContextual(ctx -> traceService.getStats(criteria))
                .map(stats -> {
                    // Transform stats to data points using the bucket start time
                    List<DataPoint<? extends Number>> dataPoints = TimeSeriesTransformer.transformToDataPoints(
                            stats,
                            request.metricType(),
                            bucket.start());

                    log.debug("Transformed trace stats to '{}' data points for bucket starting at '{}'",
                            dataPoints.size(), bucket.start());

                    return dataPoints;
                })
                .onErrorResume(error -> {
                    log.error("Error fetching trace stats for bucket starting at '{}': '{}'",
                            bucket.start(), error.getMessage(), error);
                    // Return empty list on error to not fail entire request
                    return Mono.just(List.of());
                });
    }

    /**
     * Fetch span stats for a single time bucket.
     */
    private Mono<List<DataPoint<? extends Number>>> getSpanStatsForBucket(
            UUID projectId,
            ProjectMetricRequest request,
            TimeBucket bucket) {

        // Build filters with time bucket boundaries
        List<com.comet.opik.api.filter.SpanFilter> filtersWithTime = new java.util.ArrayList<>();

        // Add time range filters for this bucket (spans also use start_time)
        filtersWithTime.add(com.comet.opik.api.filter.SpanFilter.builder()
                .field(com.comet.opik.api.filter.SpanField.START_TIME)
                .operator(com.comet.opik.api.filter.Operator.GREATER_THAN_EQUAL)
                .value(bucket.start().toString())
                .build());

        filtersWithTime.add(com.comet.opik.api.filter.SpanFilter.builder()
                .field(com.comet.opik.api.filter.SpanField.START_TIME)
                .operator(com.comet.opik.api.filter.Operator.LESS_THAN)
                .value(bucket.end().toString())
                .build());

        // Build search criteria for spans
        SpanSearchCriteria criteria = SpanSearchCriteria.builder()
                .projectId(projectId)
                .filters(filtersWithTime)
                .build();

        log.debug("Fetching span stats for bucket '{}' to '{}'", bucket.start(), bucket.end());

        // Call existing span stats endpoint with context propagation
        return Mono.deferContextual(ctx -> spanService.getStats(criteria))
                .map(stats -> {
                    // Transform stats to data points using the bucket start time
                    List<DataPoint<? extends Number>> dataPoints = TimeSeriesTransformer.transformToSpanDataPoints(
                            stats,
                            request.metricType(),
                            bucket.start());

                    log.debug("Transformed span stats to '{}' data points for bucket starting at '{}'",
                            dataPoints.size(), bucket.start());

                    return dataPoints;
                })
                .onErrorResume(error -> {
                    log.error("Error fetching span stats for bucket starting at '{}': '{}'",
                            bucket.start(), error.getMessage(), error);
                    // Return empty list on error to not fail entire request
                    return Mono.just(List.of());
                });
    }

    /**
     * Check if a metric requires span-level data vs trace-level data.
     */
    private boolean isSpanMetric(MetricType metricType) {
        return switch (metricType) {
            case SPAN_TOTAL_COUNT, SPAN_ERROR_COUNT, SPAN_INPUT_COUNT, SPAN_OUTPUT_COUNT,
                    SPAN_METADATA_COUNT, SPAN_TAGS_AVERAGE, SPAN_COST, SPAN_AVG_COST,
                    SPAN_FEEDBACK_SCORES, SPAN_TOKEN_USAGE, SPAN_PROMPT_TOKENS,
                    SPAN_COMPLETION_TOKENS, SPAN_TOTAL_TOKENS, SPAN_DURATION ->
                true;
            default -> false;
        };
    }

    /**
     * Aggregate all data points and group by series name for response.
     */
    private ProjectMetricResponse<? extends Number> aggregateAndGroupDataPoints(
            UUID projectId,
            ProjectMetricRequest request,
            List<List<DataPoint<? extends Number>>> dataPointsList) {

        // Flatten all data points
        List<DataPoint<? extends Number>> allDataPoints = dataPointsList.stream()
                .flatMap(List::stream)
                .toList();

        if (allDataPoints.isEmpty()) {
            log.warn("No data points generated for metric '{}' in project '{}'",
                    request.metricType(), projectId);
            return ProjectMetricResponse.<Number>builder()
                    .projectId(projectId)
                    .metricType(request.metricType())
                    .interval(request.interval())
                    .results(List.of())
                    .build();
        }

        // Group data points by series (for metrics like feedback_scores that have multiple series)
        List<ProjectMetricResponse.Results<? extends Number>> results = groupDataPointsBySeries(
                allDataPoints, request.metricType());

        log.info("Aggregated '{}' total data points into '{}' series for metric '{}'",
                allDataPoints.size(), results.size(), request.metricType());

        @SuppressWarnings("unchecked")
        List<ProjectMetricResponse.Results<Number>> typedResults = (List<ProjectMetricResponse.Results<Number>>) (List<?>) results;

        return ProjectMetricResponse.builder()
                .projectId(projectId)
                .metricType(request.metricType())
                .interval(request.interval())
                .results(typedResults)
                .build();
    }

    /**
     * Group data points by series name.
     * For simple metrics (trace_count), there's one series.
     * For complex metrics (feedback_scores, token_usage), there are multiple series.
     */
    private List<ProjectMetricResponse.Results<? extends Number>> groupDataPointsBySeries(
            List<DataPoint<? extends Number>> dataPoints,
            MetricType metricType) {

        // For now, create a single series with the metric name
        // TODO: Enhance to handle multi-series metrics properly
        String seriesName = getSeriesName(metricType);

        @SuppressWarnings("unchecked")
        List<DataPoint<Number>> typedDataPoints = (List<DataPoint<Number>>) (List<?>) dataPoints;

        return List.of(ProjectMetricResponse.Results.<Number>builder()
                .name(seriesName)
                .data(typedDataPoints)
                .build());
    }

    /**
     * Get the series name for a metric type.
     */
    private String getSeriesName(MetricType metricType) {
        return switch (metricType) {
            // Trace metrics
            case TRACE_COUNT -> "traces";
            case INPUT_COUNT -> "input";
            case OUTPUT_COUNT -> "output";
            case METADATA_COUNT -> "metadata";
            case ERROR_COUNT -> "errors";
            case GUARDRAILS_FAILED_COUNT -> "failed";
            case SPAN_COUNT -> "spans_per_trace";
            case LLM_SPAN_COUNT -> "llm_spans_per_trace";
            case TAGS_AVERAGE -> "tags";
            case COST -> "cost";
            case AVG_COST_PER_TRACE -> "avg_cost";
            case DURATION -> "duration";
            case TRACE_WITH_ERRORS_PERCENT -> "error_percent";
            case GUARDRAILS_PASS_RATE -> "pass_rate";

            // Span metrics
            case SPAN_TOTAL_COUNT -> "spans";
            case SPAN_ERROR_COUNT -> "span_errors";
            case SPAN_INPUT_COUNT -> "span_input";
            case SPAN_OUTPUT_COUNT -> "span_output";
            case SPAN_METADATA_COUNT -> "span_metadata";
            case SPAN_TAGS_AVERAGE -> "span_tags";
            case SPAN_COST -> "span_cost";
            case SPAN_AVG_COST -> "span_avg_cost";
            case SPAN_DURATION -> "span_duration";

            // Multi-series metrics
            case TOKEN_USAGE, COMPLETION_TOKENS, PROMPT_TOKENS, TOTAL_TOKENS -> "tokens";
            case FEEDBACK_SCORES -> "feedback_scores";
            case SPAN_FEEDBACK_SCORES -> "span_feedback_scores";
            case SPAN_TOKEN_USAGE, SPAN_PROMPT_TOKENS, SPAN_COMPLETION_TOKENS, SPAN_TOTAL_TOKENS -> "span_tokens";

            // Thread metrics (not yet supported)
            case THREAD_COUNT, THREAD_DURATION, THREAD_FEEDBACK_SCORES ->
                throw new UnsupportedOperationException("Unsupported metric: " + metricType);
        };
    }

    /**
     * Check if a metric is not yet supported by the aggregator service.
     * These metrics require different data sources or special handling.
     */
    private boolean isUnsupportedMetric(MetricType metricType) {
        return switch (metricType) {
            // Thread metrics require thread endpoint
            case THREAD_COUNT, THREAD_DURATION, THREAD_FEEDBACK_SCORES -> true;
            // All other metrics are now supported (trace or span stats)
            default -> false;
        };
    }
}
