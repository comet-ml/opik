package com.comet.opik.domain;

import com.comet.opik.api.ChartPosition;
import com.comet.opik.api.ChartType;
import com.comet.opik.api.Dashboard;
import com.comet.opik.api.DashboardChart;
import com.comet.opik.api.DashboardType;
import com.comet.opik.api.DataSeries;
import com.comet.opik.api.metrics.MetricType;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service responsible for creating prebuilt (default) dashboards for projects.
 * When a project is created, this service automatically generates a dashboard
 * with standard charts for monitoring common metrics.
 */
@ImplementedBy(PrebuiltDashboardServiceImpl.class)
public interface PrebuiltDashboardService {

    /**
     * Create a prebuilt dashboard for a project with standard monitoring charts.
     *
     * @param projectId The project ID to create the dashboard for
     * @param workspaceId The workspace ID
     * @param userName The user creating the dashboard
     * @return The created dashboard
     */
    Dashboard createPrebuiltDashboard(UUID projectId, String workspaceId, String userName);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class PrebuiltDashboardServiceImpl implements PrebuiltDashboardService {

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull DashboardService dashboardService;
    private final @NonNull DashboardChartService chartService;

    @Override
    public Dashboard createPrebuiltDashboard(@NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull String userName) {

        log.info("Creating prebuilt dashboard for project '{}' on workspace '{}'", projectId, workspaceId);

        // Create the prebuilt dashboard
        UUID dashboardId = idGenerator.generateId();
        Dashboard dashboard = Dashboard.builder()
                .id(dashboardId)
                .workspaceId(workspaceId)
                .name("Project Overview")
                .description("Default dashboard with key project metrics")
                .type(DashboardType.PREBUILT)
                .isDefault(true)
                .projectIds(List.of(projectId))
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();

        // Create the dashboard (this will save it and set up project associations)
        Dashboard createdDashboard = dashboardService.create(dashboard);

        // Create standard charts
        List<DashboardChart> charts = createStandardCharts(dashboardId, projectId, userName);

        // Save all charts
        for (DashboardChart chart : charts) {
            chartService.create(chart);
        }

        log.info("Created prebuilt dashboard '{}' with {} charts for project '{}'",
                dashboardId, charts.size(), projectId);

        // Set as default dashboard for the project
        dashboardService.setAsDefault(dashboardId, projectId);

        return createdDashboard;
    }

    /**
     * Creates a list of standard charts for the prebuilt dashboard.
     * These charts provide comprehensive monitoring out of the box.
     */
    private List<DashboardChart> createStandardCharts(UUID dashboardId, UUID projectId, String userName) {
        List<DashboardChart> charts = new ArrayList<>();

        // Chart 1: Trace Count (top-left)
        charts.add(createChart(
                dashboardId,
                projectId,
                "Trace Count",
                "Number of traces over time",
                MetricType.TRACE_COUNT,
                ChartType.LINE,
                0, 0, 2, 1,
                userName));

        // Chart 2: Trace Duration (top-right)
        charts.add(createChart(
                dashboardId,
                projectId,
                "Trace Duration",
                "P50, P90, P99 latency percentiles",
                MetricType.DURATION,
                ChartType.LINE,
                2, 0, 2, 1,
                userName));

        // Chart 3: Feedback Scores (middle-left)
        charts.add(createChart(
                dashboardId,
                projectId,
                "Feedback Scores",
                "Average feedback scores over time",
                MetricType.FEEDBACK_SCORES,
                ChartType.LINE,
                0, 1, 2, 1,
                userName));

        // Chart 4: Token Usage (middle-right)
        charts.add(createChart(
                dashboardId,
                projectId,
                "Token Usage",
                "Token consumption over time",
                MetricType.TOKEN_USAGE,
                ChartType.LINE,
                2, 1, 2, 1,
                userName));

        // Chart 5: Cost (bottom-left)
        charts.add(createChart(
                dashboardId,
                projectId,
                "Estimated Cost",
                "Total estimated cost over time",
                MetricType.COST,
                ChartType.BAR,
                0, 2, 2, 1,
                userName));

        // Chart 6: Failed Guardrails (bottom-right)
        charts.add(createChart(
                dashboardId,
                projectId,
                "Failed Guardrails",
                "Number of failed guardrails over time",
                MetricType.GUARDRAILS_FAILED_COUNT,
                ChartType.BAR,
                2, 2, 2, 1,
                userName));

        return charts;
    }

    /**
     * Helper method to create a chart with a single data series.
     */
    private DashboardChart createChart(
            UUID dashboardId,
            UUID projectId,
            String name,
            String description,
            MetricType metricType,
            ChartType chartType,
            int x, int y, int width, int height,
            String userName) {

        UUID chartId = idGenerator.generateId();
        UUID seriesId = idGenerator.generateId();

        // Create a single data series for the chart (no filters = all data)
        DataSeries dataSeries = DataSeries.builder()
                .id(seriesId)
                .projectId(projectId)
                .metricType(metricType)
                .name(metricType.name())
                .filters(List.of()) // No filters - show all data
                .order(0)
                .createdAt(Instant.now())
                .build();

        // Create chart position
        ChartPosition position = ChartPosition.builder()
                .x(x)
                .y(y)
                .width(width)
                .height(height)
                .build();

        return DashboardChart.builder()
                .id(chartId)
                .dashboardId(dashboardId)
                .name(name)
                .description(description)
                .chartType(chartType)
                .position(position)
                .dataSeries(List.of(dataSeries))
                .groupBy(null) // No grouping for prebuilt charts
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();
    }
}
