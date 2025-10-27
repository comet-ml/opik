package com.comet.opik.domain;

import com.comet.opik.api.ChartType;
import com.comet.opik.api.DashboardChart;
import com.comet.opik.infrastructure.db.DashboardChartRowMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterRowMapper(DashboardChartRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface DashboardChartDAO {

    @SqlUpdate("INSERT INTO dashboard_charts (id, dashboard_id, name, description, chart_type, position_x, position_y, width, height, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :bean.dashboardId, :bean.name, :bean.description, :bean.chartType, :positionX, :positionY, :width, :height, :bean.createdBy, :bean.lastUpdatedBy)")
    void save(@BindMethods("bean") DashboardChart chart,
            @Bind("positionX") int positionX,
            @Bind("positionY") int positionY,
            @Bind("width") int width,
            @Bind("height") int height);

    @SqlUpdate("UPDATE dashboard_charts SET " +
            "name = COALESCE(:name, name), " +
            "description = COALESCE(:description, description), " +
            "chart_type = COALESCE(:chartType, chart_type), " +
            "position_x = COALESCE(:positionX, position_x), " +
            "position_y = COALESCE(:positionY, position_y), " +
            "width = COALESCE(:width, width), " +
            "height = COALESCE(:height, height), " +
            "last_updated_by = :lastUpdatedBy " +
            "WHERE id = :id")
    void update(@Bind("id") UUID id,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("chartType") ChartType chartType,
            @Bind("positionX") Integer positionX,
            @Bind("positionY") Integer positionY,
            @Bind("width") Integer width,
            @Bind("height") Integer height,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("DELETE FROM dashboard_charts WHERE id = :id")
    void delete(@Bind("id") UUID id);

    @SqlUpdate("DELETE FROM dashboard_charts WHERE id IN (<ids>)")
    void delete(@BindList("ids") Set<UUID> ids);

    @SqlUpdate("DELETE FROM dashboard_charts WHERE dashboard_id = :dashboardId")
    void deleteByDashboardId(@Bind("dashboardId") UUID dashboardId);

    @SqlQuery("SELECT * FROM dashboard_charts WHERE id = :id")
    DashboardChart findById(@Bind("id") UUID id);

    @SqlQuery("SELECT * FROM dashboard_charts WHERE dashboard_id = :dashboardId ORDER BY position_y, position_x")
    List<DashboardChart> findByDashboardId(@Bind("dashboardId") UUID dashboardId);

    default Optional<DashboardChart> fetch(UUID id) {
        return Optional.ofNullable(findById(id));
    }
}
