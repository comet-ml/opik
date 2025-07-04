package com.comet.opik.domain;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.DashboardPanel;
import com.comet.opik.api.DashboardSection;
import com.comet.opik.api.ExperimentDashboard;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonNodeArgumentFactory.class)
@RegisterRowMapper(DashboardDAO.DashboardMapper.class)
@RegisterRowMapper(DashboardDAO.DashboardSectionMapper.class)
@RegisterRowMapper(DashboardDAO.DashboardPanelMapper.class)
@RegisterRowMapper(DashboardDAO.ExperimentDashboardMapper.class)
public interface DashboardDAO {

    @SqlUpdate("""
            INSERT INTO dashboard_templates (id, name, description, workspace_id, created_by, last_updated_by)
            VALUES (:id, :name, :description, :workspaceId, :userName, :userName)
            """)
    void insert(@Bind("id") UUID id,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE dashboard_templates
            SET name = :name, description = :description, last_updated_by = :userName
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    int update(@Bind("id") UUID id,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            DELETE FROM dashboard_templates
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    int delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE FROM dashboard_templates
            WHERE id IN (<ids>) AND workspace_id = :workspaceId
            """)
    int delete(@Bind("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT id, name, description, workspace_id, created_at, created_by, last_updated_at, last_updated_by
            FROM dashboard_templates
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    Optional<Dashboard> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT id, name, description, workspace_id, created_at, created_by, last_updated_at, last_updated_by
            FROM dashboard_templates
            WHERE workspace_id = :workspaceId
            ORDER BY created_at DESC
            """)
    List<Dashboard> findAll(@Bind("workspaceId") String workspaceId);

    // Dashboard Sections
    @SqlUpdate("""
            INSERT INTO dashboard_sections (id, dashboard_id, title, position_order, created_by, last_updated_by)
            VALUES (:id, :dashboardId, :title, :positionOrder, :userName, :userName)
            """)
    void insertSection(@Bind("id") UUID id,
            @Bind("dashboardId") UUID dashboardId,
            @Bind("title") String title,
            @Bind("positionOrder") Integer positionOrder,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE dashboard_sections
            SET title = :title, position_order = :positionOrder, last_updated_by = :userName, last_updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = :id
            """)
    int updateSection(@Bind("id") UUID id,
            @Bind("title") String title,
            @Bind("positionOrder") Integer positionOrder,
            @Bind("userName") String userName);

    @SqlUpdate("""
            DELETE FROM dashboard_sections
            WHERE id IN (<sectionIds>)
            """)
    void deleteSectionsByIds(@BindList("sectionIds") List<UUID> sectionIds);

    @SqlUpdate("""
            DELETE FROM dashboard_sections
            WHERE dashboard_id = :dashboardId
            """)
    void deleteSectionsByDashboardId(@Bind("dashboardId") UUID dashboardId);

    @SqlQuery("""
            SELECT id, dashboard_id, title, position_order, created_at, created_by, last_updated_at, last_updated_by
            FROM dashboard_sections
            WHERE dashboard_id = :dashboardId
            ORDER BY position_order
            """)
    List<DashboardSection> findSectionsByDashboardId(@Bind("dashboardId") UUID dashboardId);

    // Dashboard Panels
    @SqlUpdate("""
            INSERT INTO dashboard_panels (id, section_id, name, type, configuration, layout, created_by, last_updated_by)
            VALUES (:id, :sectionId, :name, :type, :configuration, :layout, :userName, :userName)
            """)
    void insertPanel(@Bind("id") UUID id,
            @Bind("sectionId") UUID sectionId,
            @Bind("name") String name,
            @Bind("type") String type,
            @Bind("configuration") JsonNode configuration,
            @Bind("layout") JsonNode layout,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE dashboard_panels
            SET name = :name, type = :type, configuration = :configuration, layout = :layout, last_updated_by = :userName, last_updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = :id
            """)
    int updatePanel(@Bind("id") UUID id,
            @Bind("name") String name,
            @Bind("type") String type,
            @Bind("configuration") JsonNode configuration,
            @Bind("layout") JsonNode layout,
            @Bind("userName") String userName);

    @SqlUpdate("""
            DELETE FROM dashboard_panels
            WHERE id IN (<panelIds>)
            """)
    void deletePanelsByIds(@BindList("panelIds") List<UUID> panelIds);

    @SqlQuery("""
            SELECT id, section_id, name, type, configuration, layout, created_at, created_by, last_updated_at, last_updated_by
            FROM dashboard_panels
            WHERE section_id IN (<sectionIds>)
            """)
    List<DashboardPanel> findPanelsBySectionIds(@BindList("sectionIds") List<UUID> sectionIds);

    @SqlQuery("""
            SELECT id, section_id, name, type, configuration, layout, created_at, created_by, last_updated_at, last_updated_by
            FROM dashboard_panels
            WHERE section_id IS NULL
            """)
    List<DashboardPanel> findOrphanedPanels();

    // Experiment Dashboard Association
    @SqlUpdate("""
            INSERT INTO experiment_dashboards (experiment_id, dashboard_id, workspace_id, created_by, last_updated_by)
            VALUES (:experimentId, :dashboardId, :workspaceId, :userName, :userName)
            ON DUPLICATE KEY UPDATE
            dashboard_id = :dashboardId, last_updated_by = :userName, last_updated_at = CURRENT_TIMESTAMP(6)
            """)
    void associateExperimentWithDashboard(@Bind("experimentId") UUID experimentId,
            @Bind("dashboardId") UUID dashboardId,
            @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            DELETE FROM experiment_dashboards
            WHERE experiment_id = :experimentId AND workspace_id = :workspaceId
            """)
    int removeExperimentDashboardAssociation(@Bind("experimentId") UUID experimentId,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT experiment_id, dashboard_id, workspace_id, created_at, created_by, last_updated_at, last_updated_by
            FROM experiment_dashboards
            WHERE experiment_id = :experimentId AND workspace_id = :workspaceId
            """)
    Optional<ExperimentDashboard> findExperimentDashboard(@Bind("experimentId") UUID experimentId,
            @Bind("workspaceId") String workspaceId);

    // Mappers
    class DashboardMapper implements org.jdbi.v3.core.mapper.RowMapper<Dashboard> {
        @Override
        public Dashboard map(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
                throws java.sql.SQLException {
            return Dashboard.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .name(rs.getString("name"))
                    .description(rs.getString("description"))
                    .workspaceId(rs.getString("workspace_id"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .createdBy(rs.getString("created_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .build();
        }
    }

    class DashboardSectionMapper implements org.jdbi.v3.core.mapper.RowMapper<DashboardSection> {
        @Override
        public DashboardSection map(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
                throws java.sql.SQLException {
            return DashboardSection.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .title(rs.getString("title"))
                    .positionOrder(rs.getInt("position_order"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .createdBy(rs.getString("created_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .build();
        }
    }

    class DashboardPanelMapper implements org.jdbi.v3.core.mapper.RowMapper<DashboardPanel> {
        @Override
        public DashboardPanel map(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
                throws java.sql.SQLException {
            try {
                JsonNode configuration = null;
                JsonNode layout = null;

                String configStr = rs.getString("configuration");
                String layoutStr = rs.getString("layout");

                if (configStr != null) {
                    configuration = com.comet.opik.utils.JsonUtils.MAPPER.readTree(configStr);
                }
                if (layoutStr != null) {
                    layout = com.comet.opik.utils.JsonUtils.MAPPER.readTree(layoutStr);
                }

                return DashboardPanel.builder()
                        .id(UUID.fromString(rs.getString("id")))
                        .sectionId(
                                rs.getString("section_id") != null ? UUID.fromString(rs.getString("section_id")) : null)
                        .name(rs.getString("name"))
                        .type(DashboardPanel.PanelType.valueOf(rs.getString("type").toUpperCase()))
                        .configuration(configuration)
                        .layout(layout)
                        .createdAt(rs.getTimestamp("created_at").toInstant())
                        .createdBy(rs.getString("created_by"))
                        .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                        .lastUpdatedBy(rs.getString("last_updated_by"))
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new RuntimeException("Error parsing JSON configuration or layout for dashboard panel", e);
            }
        }
    }

    class ExperimentDashboardMapper implements org.jdbi.v3.core.mapper.RowMapper<ExperimentDashboard> {
        @Override
        public ExperimentDashboard map(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
                throws java.sql.SQLException {
            return ExperimentDashboard.builder()
                    .experimentId(UUID.fromString(rs.getString("experiment_id")))
                    .dashboardId(UUID.fromString(rs.getString("dashboard_id")))
                    .workspaceId(rs.getString("workspace_id"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .createdBy(rs.getString("created_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .build();
        }
    }
}
