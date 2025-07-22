package com.comet.opik.domain;

import com.comet.opik.api.dashboard.Dashboard;
import com.comet.opik.api.dashboard.DashboardUpdate;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(DashboardDAO.DashboardEntity.class)
public interface DashboardDAO {

    record DashboardEntity(
            UUID id,
            String name,
            String description,
            String layout, // JSON as string
            String filters, // JSON as string
            Integer refreshInterval,
            Instant createdAt,
            String createdBy,
            Instant lastUpdatedAt,
            String lastUpdatedBy) {
    }

    @SqlUpdate("INSERT INTO dashboards(id, name, description, layout, filters, refresh_interval, workspace_id, created_by, last_updated_by) "
            + "VALUES (:dashboard.id, :dashboard.name, :dashboard.description, :layoutJson, :filtersJson, :dashboard.refreshInterval, :workspaceId, :dashboard.createdBy, :dashboard.lastUpdatedBy)")
    void save(@BindMethods("dashboard") Dashboard dashboard,
            @Bind("layoutJson") String layoutJson,
            @Bind("filtersJson") String filtersJson,
            @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE dashboards SET
                name = COALESCE(:update.name, name),
                description = COALESCE(:update.description, description),
                layout = COALESCE(:layoutJson, layout),
                filters = COALESCE(:filtersJson, filters),
                refresh_interval = COALESCE(:update.refreshInterval, refresh_interval),
                last_updated_by = :lastUpdatedBy,
                last_updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    boolean update(@Bind("id") UUID id,
            @BindMethods("update") DashboardUpdate update,
            @Bind("layoutJson") String layoutJson,
            @Bind("filtersJson") String filtersJson,
            @Bind("lastUpdatedBy") String lastUpdatedBy,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT id, name, description, layout, filters, refresh_interval, created_at, created_by, last_updated_at, last_updated_by "
            + "FROM dashboards WHERE id = :id AND workspace_id = :workspaceId")
    Optional<DashboardEntity> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM dashboards WHERE id = :id AND workspace_id = :workspaceId")
    boolean deleteById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @UseStringTemplateEngine
    @SqlQuery("""
            SELECT id, name, description, layout, filters, refresh_interval, created_at, created_by, last_updated_at, last_updated_by
            FROM dashboards
            WHERE workspace_id = :workspaceId
            <if(search)>
                AND name LIKE CONCAT('%', :search, '%')
            <endif>
            ORDER BY
            <if(sortField)>
                <sortField> <sortDirection>
            <else>
                created_at DESC
            <endif>
            LIMIT :limit OFFSET :offset
            """)
    List<DashboardEntity> findByWorkspaceId(@Bind("workspaceId") String workspaceId,
            @Define("search") String search,
            @Define("sortField") String sortField,
            @Define("sortDirection") String sortDirection,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @UseStringTemplateEngine
    @SqlQuery("""
            SELECT COUNT(*)
            FROM dashboards
            WHERE workspace_id = :workspaceId
            <if(search)>
                AND name LIKE CONCAT('%', :search, '%')
            <endif>
            """)
    long countByWorkspaceId(@Bind("workspaceId") String workspaceId, @Define("search") String search);
}
