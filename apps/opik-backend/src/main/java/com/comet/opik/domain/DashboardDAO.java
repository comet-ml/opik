package com.comet.opik.domain;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.DashboardType;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterConstructorMapper(Dashboard.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface DashboardDAO {

    @SqlUpdate("INSERT INTO dashboards (id, workspace_id, name, description, dashboard_type, is_default, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :workspaceId, :bean.name, :bean.description, :bean.type, :bean.isDefault, :bean.createdBy, :bean.lastUpdatedBy)")
    void save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Dashboard dashboard);

    @SqlUpdate("UPDATE dashboards SET " +
            "name = COALESCE(:name, name), " +
            "description = COALESCE(:description, description), " +
            "last_updated_by = :lastUpdatedBy " +
            "WHERE id = :id AND workspace_id = :workspaceId")
    void update(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("DELETE FROM dashboards WHERE id = :id AND workspace_id = :workspaceId")
    void delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM dashboards WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM dashboards WHERE id = :id AND workspace_id = :workspaceId")
    Dashboard findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM dashboards WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    List<Dashboard> findByIds(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT COUNT(*) FROM dashboards " +
            " WHERE workspace_id = :workspaceId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(dashboardType)> AND dashboard_type = :dashboardType <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspaceId") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("dashboardType") @Bind("dashboardType") DashboardType dashboardType);

    @SqlQuery("SELECT * FROM dashboards " +
            " WHERE workspace_id = :workspaceId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(dashboardType)> AND dashboard_type = :dashboardType <endif> " +
            " ORDER BY <if(sort_fields)> <sort_fields>, <endif> created_at DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Dashboard> find(@Bind("limit") int limit,
            @Bind("offset") int offset,
            @Bind("workspaceId") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("dashboardType") @Bind("dashboardType") DashboardType dashboardType,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields);

    @SqlQuery("SELECT d.* FROM dashboards d " +
            "JOIN dashboard_projects dp ON d.id = dp.dashboard_id " +
            "WHERE dp.project_id = :projectId AND d.workspace_id = :workspaceId")
    List<Dashboard> findByProjectId(@Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT d.* FROM dashboards d " +
            "JOIN dashboard_projects dp ON d.id = dp.dashboard_id " +
            "WHERE dp.project_id = :projectId AND dp.is_default = TRUE AND d.workspace_id = :workspaceId " +
            "LIMIT 1")
    Dashboard findDefaultByProjectId(@Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    default Optional<Dashboard> fetch(UUID id, String workspaceId) {
        return Optional.ofNullable(findById(id, workspaceId));
    }
}
