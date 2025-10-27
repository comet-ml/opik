package com.comet.opik.domain;

import com.comet.opik.api.DataSeries;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.infrastructure.db.ListFilterArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterConstructorMapper(DataSeries.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(ListFilterArgumentFactory.class)
@RegisterColumnMapper(ListFilterArgumentFactory.class)
interface ChartDataSeriesDAO {

    @SqlUpdate("INSERT INTO chart_data_series (id, chart_id, project_id, metric_type, name, filters, color, series_order) "
            +
            "VALUES (:bean.id, :chartId, :bean.projectId, :bean.metricType, :bean.name, :filters, :bean.color, :bean.order)")
    void save(@Bind("chartId") UUID chartId,
            @BindMethods("bean") DataSeries dataSeries,
            @Bind("filters") String filters);

    @SqlUpdate("UPDATE chart_data_series SET " +
            "project_id = COALESCE(:projectId, project_id), " +
            "metric_type = COALESCE(:metricType, metric_type), " +
            "name = COALESCE(:name, name), " +
            "filters = COALESCE(:filters, filters), " +
            "color = COALESCE(:color, color), " +
            "series_order = COALESCE(:order, series_order) " +
            "WHERE id = :id")
    void update(@Bind("id") UUID id,
            @Bind("projectId") UUID projectId,
            @Bind("metricType") MetricType metricType,
            @Bind("name") String name,
            @Bind("filters") String filters,
            @Bind("color") String color,
            @Bind("order") Integer order);

    @SqlUpdate("DELETE FROM chart_data_series WHERE id = :id")
    void delete(@Bind("id") UUID id);

    @SqlUpdate("DELETE FROM chart_data_series WHERE id IN (<ids>)")
    void delete(@BindList("ids") Set<UUID> ids);

    @SqlUpdate("DELETE FROM chart_data_series WHERE chart_id = :chartId")
    void deleteByChartId(@Bind("chartId") UUID chartId);

    @SqlQuery("SELECT * FROM chart_data_series WHERE id = :id")
    DataSeries findById(@Bind("id") UUID id);

    @SqlQuery("SELECT * FROM chart_data_series WHERE chart_id = :chartId ORDER BY series_order")
    List<DataSeries> findByChartId(@Bind("chartId") UUID chartId);

    default Optional<DataSeries> fetch(UUID id) {
        return Optional.ofNullable(findById(id));
    }
}
