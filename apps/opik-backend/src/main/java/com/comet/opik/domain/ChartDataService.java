package com.comet.opik.domain;

import com.comet.opik.api.ChartDataRequest;
import com.comet.opik.api.ChartDataResponse;
import com.comet.opik.api.DashboardChart;
import com.comet.opik.api.DataSeries;
import com.comet.opik.api.SeriesData;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@ImplementedBy(ChartDataServiceImpl.class)
public interface ChartDataService {

    Mono<ChartDataResponse> getChartData(UUID chartId, ChartDataRequest request);

    Mono<ChartDataResponse> getChartPreviewData(DashboardChart chart, ChartDataRequest request);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ChartDataServiceImpl implements ChartDataService {

    private final @NonNull DashboardChartService dashboardChartService;
    private final @NonNull ProjectMetricsService projectMetricsService;

    @Override
    public Mono<ChartDataResponse> getChartData(@NonNull UUID chartId, @NonNull ChartDataRequest request) {
        // Get chart configuration
        DashboardChart chart = dashboardChartService.get(chartId);

        if (chart.dataSeries() == null || chart.dataSeries().isEmpty()) {
            // No data series configured, return empty response
            return Mono.just(ChartDataResponse.builder()
                    .chartId(chartId)
                    .interval(request.interval())
                    .series(List.of())
                    .build());
        }

        // Fetch data for each series
        List<Mono<SeriesData>> seriesMonos = chart.dataSeries().stream()
                .map(dataSeries -> fetchSeriesData(dataSeries, request))
                .toList();

        // Combine all series data
        return Flux.fromIterable(seriesMonos)
                .flatMap(mono -> mono)
                .collectList()
                .map(seriesDataList -> ChartDataResponse.builder()
                        .chartId(chartId)
                        .interval(request.interval())
                        .series(seriesDataList)
                        .build())
                .doOnSuccess(response -> log.info("Fetched data for chart '{}' with {} series",
                        chartId, response.series().size()));
    }

    @Override
    public Mono<ChartDataResponse> getChartPreviewData(@NonNull DashboardChart chart,
            @NonNull ChartDataRequest request) {
        log.info("Fetching preview data for chart configuration with {} series",
                chart.dataSeries() != null ? chart.dataSeries().size() : 0);
        log.info("Chart object: name={}, type={}, dataSeries={}",
                chart.name(), chart.chartType(), chart.dataSeries());

        if (chart.dataSeries() == null || chart.dataSeries().isEmpty()) {
            // No data series configured, return empty response
            log.info("No data series configured, returning empty response");
            return Mono.just(ChartDataResponse.builder()
                    .chartId(null) // No chart ID for preview
                    .interval(request.interval())
                    .series(List.of())
                    .build());
        }

        log.info("Building {} series for preview", chart.dataSeries().size());

        // Fetch data for each series - Create Monos LAZILY inside the pipeline
        return Flux.fromIterable(chart.dataSeries())
                .flatMap(dataSeries -> Mono.deferContextual(ctx -> {
                    log.info("Deferred: Fetching data for series '{}'", dataSeries.name());
                    return fetchSeriesData(dataSeries, request);
                }))
                .collectList()
                .map(seriesDataList -> ChartDataResponse.builder()
                        .chartId(null) // No chart ID for preview
                        .interval(request.interval())
                        .series(seriesDataList)
                        .build())
                .doOnSuccess(response -> log.info("Fetched preview data with {} series", response.series().size()));
    }

    private Mono<SeriesData> fetchSeriesData(DataSeries dataSeries, ChartDataRequest request) {
        // Build metric request
        ProjectMetricRequest metricRequest = ProjectMetricRequest.builder()
                .metricType(dataSeries.metricType())
                .interval(request.interval())
                .intervalStart(request.intervalStart())
                .intervalEnd(request.intervalEnd())
                .traceFilters(extractTraceFilters(dataSeries.filters()))
                .threadFilters(extractThreadFilters(dataSeries.filters()))
                .build();

        UUID projectId = dataSeries.projectId();
        String seriesName = dataSeries.name() != null ? dataSeries.name() : "Unnamed";

        if (projectId == null) {
            log.warn("Data series '{}' has no project ID, skipping", seriesName);
            return Mono.just(SeriesData.builder()
                    .name(seriesName)
                    .data(List.of())
                    .build());
        }

        log.info("Fetching metrics for series '{}', project: {}, metric: {}", seriesName, projectId,
                dataSeries.metricType());

        // Fetch metrics data
        return projectMetricsService.getProjectMetrics(projectId, metricRequest)
                .doOnNext(response -> log.info("Got metrics response for series '{}' with {} results",
                        seriesName, response.results() != null ? response.results().size() : 0))
                .map(response -> convertToSeriesData(dataSeries, response))
                .doOnNext(seriesData -> log.info("Converted to SeriesData '{}' with {} data points",
                        seriesData.name(), seriesData.data().size()))
                .onErrorResume(error -> {
                    log.error("Error fetching data for series '{}': {}", seriesName, error.getMessage(), error);
                    return Mono.just(SeriesData.builder()
                            .name(seriesName)
                            .data(List.of())
                            .build());
                });
    }

    private SeriesData convertToSeriesData(DataSeries dataSeries, ProjectMetricResponse<Number> response) {
        String seriesName = dataSeries.name() != null ? dataSeries.name() : response.metricType().name();

        // If there are multiple results (e.g., from grouping), take the first one
        // In future, grouping will be handled separately
        if (response.results() != null && !response.results().isEmpty()) {
            return SeriesData.builder()
                    .name(seriesName)
                    .data(new ArrayList<>(response.results().get(0).data()))
                    .build();
        }

        return SeriesData.builder()
                .name(seriesName)
                .data(List.of())
                .build();
    }

    private List<TraceFilter> extractTraceFilters(List<Filter> filters) {
        if (filters == null) {
            return List.of();
        }

        return filters.stream()
                .filter(filter -> filter instanceof TraceFilter)
                .map(filter -> (TraceFilter) filter)
                .collect(Collectors.toList());
    }

    private List<TraceThreadFilter> extractThreadFilters(List<Filter> filters) {
        if (filters == null) {
            return List.of();
        }

        return filters.stream()
                .filter(filter -> filter instanceof TraceThreadFilter)
                .map(filter -> (TraceThreadFilter) filter)
                .collect(Collectors.toList());
    }
}
