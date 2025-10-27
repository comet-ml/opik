package com.comet.opik.domain;

import com.comet.opik.api.ChartPosition;
import com.comet.opik.api.DashboardChart;
import com.comet.opik.api.DataSeries;
import com.comet.opik.api.GroupByConfig;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ImplementedBy(DashboardChartServiceImpl.class)
public interface DashboardChartService {

    DashboardChart create(DashboardChart chart);

    DashboardChart update(UUID id, DashboardChart chart);

    DashboardChart get(UUID id);

    void delete(UUID id);

    List<DashboardChart> findByDashboardId(UUID dashboardId);

    DashboardChart clone(UUID chartId, UUID targetDashboardId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class DashboardChartServiceImpl implements DashboardChartService {

    private static final String CHART_NOT_FOUND = "Chart not found";

    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;

    private NotFoundException createNotFoundError() {
        log.info(CHART_NOT_FOUND);
        return new NotFoundException(CHART_NOT_FOUND,
                Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorMessage(List.of(CHART_NOT_FOUND)))
                        .build());
    }

    @Override
    public DashboardChart create(@NonNull DashboardChart chart) {
        UUID chartId = idGenerator.generateId();
        String userName = requestContext.get().getUserName();

        DashboardChart newChart = chart.toBuilder()
                .id(chartId)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();

        return template.inTransaction(handle -> {
            var chartDAO = handle.attach(DashboardChartDAO.class);

            // Extract position
            ChartPosition position = newChart.position() != null
                    ? newChart.position()
                    : ChartPosition.builder().x(0).y(0).width(1).height(1).build();

            chartDAO.save(newChart, position.x(), position.y(), position.width(), position.height());

            // Save data series
            if (chart.dataSeries() != null && !chart.dataSeries().isEmpty()) {
                var dataSeriesDAO = handle.attach(ChartDataSeriesDAO.class);

                for (DataSeries series : chart.dataSeries()) {
                    UUID seriesId = idGenerator.generateId();
                    DataSeries newSeries = series.toBuilder()
                            .id(seriesId)
                            .createdAt(Instant.now())
                            .build();

                    String filtersJson = series.filters() != null
                            ? JsonUtils.writeValueAsString(series.filters())
                            : null;

                    dataSeriesDAO.save(chartId, newSeries, filtersJson);
                }
            }

            // Save group by configuration
            if (chart.groupBy() != null) {
                var groupingDAO = handle.attach(ChartGroupingDAO.class);
                UUID groupingId = idGenerator.generateId();
                groupingDAO.save(groupingId, chartId, chart.groupBy());
            }

            log.info("Created chart with id '{}', name '{}'", chartId, chart.name());
            return newChart;
        });
    }

    @Override
    public DashboardChart update(@NonNull UUID id, @NonNull DashboardChart chart) {
        String userName = requestContext.get().getUserName();

        return template.inTransaction(handle -> {
            var chartDAO = handle.attach(DashboardChartDAO.class);

            // Verify chart exists
            DashboardChart existing = chartDAO.findById(id);
            if (existing == null) {
                throw createNotFoundError();
            }

            // Extract position if provided
            Integer posX = chart.position() != null ? chart.position().x() : null;
            Integer posY = chart.position() != null ? chart.position().y() : null;
            Integer width = chart.position() != null ? chart.position().width() : null;
            Integer height = chart.position() != null ? chart.position().height() : null;

            // Update chart
            chartDAO.update(id, chart.name(), chart.description(), chart.chartType(),
                    posX, posY, width, height, userName);

            // Update data series if provided
            if (chart.dataSeries() != null) {
                var dataSeriesDAO = handle.attach(ChartDataSeriesDAO.class);

                // Remove old series and add new ones
                dataSeriesDAO.deleteByChartId(id);

                for (DataSeries series : chart.dataSeries()) {
                    UUID seriesId = series.id() != null ? series.id() : idGenerator.generateId();
                    DataSeries newSeries = series.toBuilder()
                            .id(seriesId)
                            .createdAt(Instant.now())
                            .build();

                    String filtersJson = series.filters() != null
                            ? JsonUtils.writeValueAsString(series.filters())
                            : null;

                    dataSeriesDAO.save(id, newSeries, filtersJson);
                }
            }

            // Update group by configuration if provided
            if (chart.groupBy() != null) {
                var groupingDAO = handle.attach(ChartGroupingDAO.class);
                UUID groupingId = idGenerator.generateId();
                groupingDAO.save(groupingId, id, chart.groupBy());
            } else {
                // Remove grouping if not provided
                var groupingDAO = handle.attach(ChartGroupingDAO.class);
                groupingDAO.deleteByChartId(id);
            }

            log.info("Updated chart with id '{}'", id);
            return get(id);
        });
    }

    @Override
    public DashboardChart get(@NonNull UUID id) {
        return template.inTransaction(handle -> {
            var chartDAO = handle.attach(DashboardChartDAO.class);
            DashboardChart chart = chartDAO.findById(id);

            if (chart == null) {
                throw createNotFoundError();
            }

            // Load data series
            var dataSeriesDAO = handle.attach(ChartDataSeriesDAO.class);
            List<DataSeries> dataSeries = dataSeriesDAO.findByChartId(id);

            // Load group by configuration
            var groupingDAO = handle.attach(ChartGroupingDAO.class);
            GroupByConfig groupBy = groupingDAO.findByChartId(id);

            return chart.toBuilder()
                    .dataSeries(dataSeries)
                    .groupBy(groupBy)
                    .build();
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        template.inTransaction(handle -> {
            var chartDAO = handle.attach(DashboardChartDAO.class);

            DashboardChart chart = chartDAO.findById(id);
            if (chart == null) {
                throw createNotFoundError();
            }

            chartDAO.delete(id);
            log.info("Deleted chart with id '{}'", id);
            return null;
        });
    }

    @Override
    public List<DashboardChart> findByDashboardId(@NonNull UUID dashboardId) {
        return template.inTransaction(handle -> {
            var chartDAO = handle.attach(DashboardChartDAO.class);
            List<DashboardChart> charts = chartDAO.findByDashboardId(dashboardId);

            // Load data series and grouping for each chart
            var dataSeriesDAO = handle.attach(ChartDataSeriesDAO.class);
            var groupingDAO = handle.attach(ChartGroupingDAO.class);

            return charts.stream()
                    .map(chart -> {
                        List<DataSeries> dataSeries = dataSeriesDAO.findByChartId(chart.id());
                        GroupByConfig groupBy = groupingDAO.findByChartId(chart.id());

                        return chart.toBuilder()
                                .dataSeries(dataSeries)
                                .groupBy(groupBy)
                                .build();
                    })
                    .toList();
        });
    }

    @Override
    public DashboardChart clone(@NonNull UUID chartId, UUID targetDashboardId) {
        return template.inTransaction(handle -> {
            // Get source chart
            DashboardChart sourceChart = get(chartId);

            // Create new chart with same configuration but different ID and dashboard
            UUID newDashboardId = targetDashboardId != null ? targetDashboardId : sourceChart.dashboardId();

            DashboardChart clonedChart = sourceChart.toBuilder()
                    .id(null)
                    .dashboardId(newDashboardId)
                    .name(sourceChart.name() + " (Copy)")
                    .build();

            log.info("Cloned chart '{}' to dashboard '{}'", chartId, newDashboardId);
            return create(clonedChart);
        });
    }
}
