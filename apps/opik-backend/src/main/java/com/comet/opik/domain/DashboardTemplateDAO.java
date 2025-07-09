package com.comet.opik.domain;

import com.comet.opik.api.DashboardTemplate;
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
@RegisterRowMapper(DashboardTemplateDAO.DashboardTemplateMapper.class)
public interface DashboardTemplateDAO {

    @SqlUpdate("""
            INSERT INTO dashboard_templates (id, name, description, configuration, workspace_id, created_by, last_updated_by)
            VALUES (:id, :name, :description, :configuration, :workspaceId, :userName, :userName)
            """)
    void insert(@Bind("id") UUID id,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("configuration") JsonNode configuration,
            @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlUpdate("""
            UPDATE dashboard_templates
            SET name = :name, description = :description, configuration = :configuration, last_updated_by = :userName, last_updated_at = CURRENT_TIMESTAMP(6)
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    int update(@Bind("id") UUID id,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("configuration") JsonNode configuration,
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
    int delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT id, name, description, configuration, workspace_id, created_at, created_by, last_updated_at, last_updated_by
            FROM dashboard_templates
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    Optional<DashboardTemplate> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT id, name, description, configuration, workspace_id, created_at, created_by, last_updated_at, last_updated_by
            FROM dashboard_templates
            WHERE workspace_id = :workspaceId
            ORDER BY created_at DESC
            """)
    List<DashboardTemplate> findAll(@Bind("workspaceId") String workspaceId);

    class DashboardTemplateMapper implements org.jdbi.v3.core.mapper.RowMapper<DashboardTemplate> {
        @Override
        public DashboardTemplate map(java.sql.ResultSet rs, org.jdbi.v3.core.statement.StatementContext ctx)
                throws java.sql.SQLException {
            try {
                JsonNode configuration = null;
                String configStr = rs.getString("configuration");
                if (configStr != null) {
                    configuration = com.comet.opik.utils.JsonUtils.MAPPER.readTree(configStr);
                }

                return DashboardTemplate.builder()
                        .id(UUID.fromString(rs.getString("id")))
                        .name(rs.getString("name"))
                        .description(rs.getString("description"))
                        .configuration(configuration)
                        .workspaceId(rs.getString("workspace_id"))
                        .createdAt(rs.getTimestamp("created_at").toInstant())
                        .createdBy(rs.getString("created_by"))
                        .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                        .lastUpdatedBy(rs.getString("last_updated_by"))
                        .build();
            } catch (Exception e) {
                throw new RuntimeException("Error parsing JSON configuration for dashboard template", e);
            }
        }
    }
}