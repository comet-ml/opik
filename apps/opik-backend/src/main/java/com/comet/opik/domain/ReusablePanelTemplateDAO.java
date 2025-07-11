package com.comet.opik.domain;

import com.comet.opik.api.DashboardPanel;
import com.comet.opik.api.ReusablePanelTemplate;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonNodeArgumentFactory.class)
@RegisterRowMapper(ReusablePanelTemplateDAO.ReusablePanelTemplateMapper.class)
public interface ReusablePanelTemplateDAO {

    @SqlUpdate("""
            INSERT INTO reusable_panel_templates (id, name, description, type, configuration, default_layout, workspace_id, created_by, last_updated_by)
            VALUES (:id, :name, :description, :type, :configuration, :defaultLayout, :workspaceId, :userName, :userName)
            """)
    void insert(@Bind("id") UUID id,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("type") String type,
            @Bind("configuration") JsonNode configuration,
            @Bind("defaultLayout") JsonNode defaultLayout,
            @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE reusable_panel_templates
            SET name = :name, description = :description, type = :type, configuration = :configuration,
                default_layout = :defaultLayout, last_updated_by = :userName, last_updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    int update(@Bind("id") UUID id,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("type") String type,
            @Bind("configuration") JsonNode configuration,
            @Bind("defaultLayout") JsonNode defaultLayout,
            @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            DELETE FROM reusable_panel_templates
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    int delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE FROM reusable_panel_templates
            WHERE id IN (<ids>) AND workspace_id = :workspaceId
            """)
    int delete(@Bind("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT id, name, description, type, configuration, default_layout, workspace_id, created_at, created_by, last_updated_at, last_updated_by
            FROM reusable_panel_templates
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    Optional<ReusablePanelTemplate> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT id, name, description, type, configuration, default_layout, workspace_id, created_at, created_by, last_updated_at, last_updated_by
            FROM reusable_panel_templates
            WHERE workspace_id = :workspaceId
            ORDER BY created_at DESC
            """)
    List<ReusablePanelTemplate> findAll(@Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT id, name, description, type, configuration, default_layout, workspace_id, created_at, created_by, last_updated_at, last_updated_by
            FROM reusable_panel_templates
            WHERE workspace_id = :workspaceId AND type = :type
            ORDER BY created_at DESC
            """)
    List<ReusablePanelTemplate> findByType(@Bind("workspaceId") String workspaceId, @Bind("type") String type);

    class ReusablePanelTemplateMapper implements org.jdbi.v3.core.mapper.RowMapper<ReusablePanelTemplate> {
        @Override
        public ReusablePanelTemplate map(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
                throws java.sql.SQLException {
            return ReusablePanelTemplate.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .name(rs.getString("name"))
                    .description(rs.getString("description"))
                    .type(DashboardPanel.PanelType.valueOf(rs.getString("type").toUpperCase()))
                    .configuration(rs.getString("configuration") != null
                            ? com.comet.opik.utils.JsonUtils.readValue(rs.getString("configuration"), JsonNode.class)
                            : null)
                    .defaultLayout(rs.getString("default_layout") != null
                            ? com.comet.opik.utils.JsonUtils.readValue(rs.getString("default_layout"), JsonNode.class)
                            : null)
                    .workspaceId(rs.getString("workspace_id"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .createdBy(rs.getString("created_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .build();
        }
    }
}