package com.comet.opik.domain;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface DashboardProjectDAO {

    @SqlUpdate("INSERT INTO dashboard_projects (id, dashboard_id, project_id, is_default) " +
            "VALUES (:id, :dashboardId, :projectId, :isDefault)")
    void save(@Bind("id") UUID id,
            @Bind("dashboardId") UUID dashboardId,
            @Bind("projectId") UUID projectId,
            @Bind("isDefault") boolean isDefault);

    @SqlBatch("INSERT INTO dashboard_projects (id, dashboard_id, project_id, is_default) " +
            "VALUES (:id, :dashboardId, :projectId, false)")
    void saveBatch(@Bind("id") List<UUID> ids,
            @Bind("dashboardId") List<UUID> dashboardIds,
            @Bind("projectId") List<UUID> projectIds);

    @SqlUpdate("DELETE FROM dashboard_projects WHERE dashboard_id = :dashboardId")
    void deleteByDashboardId(@Bind("dashboardId") UUID dashboardId);

    @SqlUpdate("DELETE FROM dashboard_projects WHERE dashboard_id = :dashboardId AND project_id IN (<projectIds>)")
    void deleteByDashboardAndProjects(@Bind("dashboardId") UUID dashboardId,
            @BindList("projectIds") Set<UUID> projectIds);

    @SqlQuery("SELECT project_id FROM dashboard_projects WHERE dashboard_id = :dashboardId")
    List<UUID> findProjectIdsByDashboardId(@Bind("dashboardId") UUID dashboardId);

    @SqlUpdate("UPDATE dashboard_projects SET is_default = FALSE " +
            "WHERE project_id = :projectId")
    void unsetAllDefaultsByProjectId(@Bind("projectId") UUID projectId);

    @SqlUpdate("UPDATE dashboard_projects SET is_default = TRUE " +
            "WHERE dashboard_id = :dashboardId AND project_id = :projectId")
    void setAsDefault(@Bind("dashboardId") UUID dashboardId, @Bind("projectId") UUID projectId);
}
