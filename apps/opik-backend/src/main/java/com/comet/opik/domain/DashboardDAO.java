package com.comet.opik.domain;

import com.comet.opik.api.dashboard.Dashboard;
import com.comet.opik.api.dashboard.DashboardUpdate;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.infrastructure.db.JsonArgumentFactory;
import com.comet.opik.infrastructure.db.JsonColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonArgumentFactory.class)
@RegisterColumnMapper(JsonColumnMapper.class)
@RegisterConstructorMapper(Dashboard.class)
public interface DashboardDAO {

    @SqlUpdate("INSERT INTO dashboards(id, name, description, layout, filters, refresh_interval, workspace_id, created_by, last_updated_by) "
            + "VALUES (:dashboard.id, :dashboard.name, :dashboard.description, CAST(:dashboard.layout AS JSONB), CAST(:dashboard.filters AS JSONB), :dashboard.refreshInterval, :workspaceId, :dashboard.createdBy, :dashboard.lastUpdatedBy)")
    void save(@BindMethods("dashboard") Dashboard dashboard, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE dashboards SET
                name = COALESCE(:update.name, name),
                description = COALESCE(:update.description, description),
                layout = COALESCE(CAST(:update.layout AS JSONB), layout),
                filters = COALESCE(CAST(:update.filters AS JSONB), filters),
                refresh_interval = COALESCE(:update.refreshInterval, refresh_interval),
                last_updated_by = :lastUpdatedBy,
                last_updated_at = CURRENT_TIMESTAMP
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    boolean update(@Bind("id") UUID id, 
                   @BindMethods("update") DashboardUpdate update, 
                   @Bind("lastUpdatedBy") String lastUpdatedBy,
                   @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT id, name, description, layout, filters, refresh_interval, created_at, created_by, last_updated_at, last_updated_by "
            + "FROM dashboards WHERE id = :id AND workspace_id = :workspaceId")
    Optional<Dashboard> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM dashboards WHERE id = :id AND workspace_id = :workspaceId")
    boolean deleteById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @UseStringTemplateEngine
    @SqlQuery("""
            SELECT id, name, description, layout, filters, refresh_interval, created_at, created_by, last_updated_at, last_updated_by
            FROM dashboards 
            WHERE workspace_id = :workspaceId
            <if(search)>
                AND (name ILIKE '%' || :search || '%' OR description ILIKE '%' || :search || '%')
            <endif>
            ORDER BY <orderBy> <sortOrder>
            LIMIT :limit OFFSET :offset
            """)
    List<Dashboard> find(
            @Bind("workspaceId") String workspaceId,
            @Bind("search") String search,
            @Bind("limit") int limit,
            @Bind("offset") int offset,
            @Define("orderBy") String orderBy,
            @Define("sortOrder") String sortOrder);

    @UseStringTemplateEngine
    @SqlQuery("""
            SELECT COUNT(*)
            FROM dashboards 
            WHERE workspace_id = :workspaceId
            <if(search)>
                AND (name ILIKE '%' || :search || '%' OR description ILIKE '%' || :search || '%')
            <endif>
            """)
    long countDashboards(
            @Bind("workspaceId") String workspaceId,
            @Bind("search") String search);

    @SqlQuery("SELECT id, name, description, layout, filters, refresh_interval, created_at, created_by, last_updated_at, last_updated_by "
            + "FROM dashboards WHERE workspace_id = :workspaceId ORDER BY created_at DESC")
    List<Dashboard> findAllByWorkspaceId(@Bind("workspaceId") String workspaceId);
}
