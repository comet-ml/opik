package com.comet.opik.domain;

import com.comet.opik.domain.AgentBlueprint.BlueprintType;
import com.comet.opik.infrastructure.db.BlueprintTypeArgumentFactory;
import com.comet.opik.infrastructure.db.BlueprintTypeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RowUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(AgentConfig.class)
@RegisterConstructorMapper(AgentConfigEnv.class)
@RegisterConstructorMapper(AgentConfigDAO.BlueprintProject.class)
@RegisterRowMapper(AgentConfigDAO.BlueprintRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(BlueprintTypeArgumentFactory.class)
@RegisterColumnMapper(BlueprintTypeColumnMapper.class)
interface AgentConfigDAO {

    record BlueprintProject(UUID id, UUID projectId) {
    }

    @SqlQuery("""
            SELECT id, project_id, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_configs
            WHERE workspace_id = :workspace_id AND project_id = :project_id
            """)
    AgentConfig getConfigByProjectId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId);

    @SqlUpdate("""
            INSERT INTO agent_configs (id, workspace_id, project_id, created_by, last_updated_by)
            VALUES (:id, :workspace_id, :project_id, :created_by, :last_updated_by)
            """)
    void insertConfig(
            @Bind("id") UUID id,
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("created_by") String createdBy,
            @Bind("last_updated_by") String lastUpdatedBy);

    @SqlUpdate("""
            INSERT INTO agent_blueprints (id, workspace_id, project_id, config_id, type, name, description, `values`, created_by, last_updated_by)
            VALUES (:id, :workspace_id, :project_id, :config_id, :type, :name, :description, :values, :created_by, :last_updated_by)
            """)
    void insertBlueprint(
            @Bind("id") UUID id,
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("config_id") UUID configId,
            @Bind("type") BlueprintType type,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("values") String valuesJson,
            @Bind("created_by") String createdBy,
            @Bind("last_updated_by") String lastUpdatedBy);

    @SqlQuery("""
            SELECT id, project_id, name, type, description, `values`, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND type = :type
            ORDER BY id DESC LIMIT 1
            """)
    AgentBlueprint getLatestBlueprint(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("type") BlueprintType type);

    @SqlQuery("""
            SELECT id, project_id, name, type, description, `values`, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND id = :blueprint_id
            """)
    AgentBlueprint getBlueprintById(
            @Bind("workspace_id") String workspaceId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("""
            SELECT id, project_id, name, type, description, `values`, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_blueprints
            WHERE workspace_id = :workspace_id
                AND id = :blueprint_id
                AND project_id = :project_id
                AND type = :type
            """)
    AgentBlueprint getBlueprintByIdAndType(
            @Bind("workspace_id") String workspaceId,
            @Bind("blueprint_id") UUID blueprintId,
            @Bind("project_id") UUID projectId,
            @Bind("type") BlueprintType type);

    @SqlQuery("""
            SELECT id, project_id, name, type, description, `values`, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_blueprints
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND name = :name
                AND type = :type
            """)
    AgentBlueprint getBlueprintByNameAndType(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("name") String name,
            @Bind("type") BlueprintType type);

    @SqlQuery("""
            SELECT id, project_id FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND id IN (<blueprint_ids>)
            """)
    List<BlueprintProject> getBlueprintsByIds(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @BindList("blueprint_ids") List<UUID> blueprintIds);

    @SqlQuery("""
            SELECT b.id, b.project_id, b.name, b.type, b.description, b.`values`,
                   b.created_by, b.created_at, b.last_updated_by, b.last_updated_at
            FROM agent_blueprints b
            JOIN agent_config_envs e
                ON e.workspace_id = b.workspace_id
                AND e.project_id = b.project_id
                AND e.blueprint_id = b.id
            WHERE b.workspace_id = :workspace_id
                AND b.project_id = :project_id
                AND b.type = 'blueprint'
                AND e.env_name = :env_name
                AND e.ended_at IS NULL
            """)
    AgentBlueprint getBlueprintByEnvName(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("env_name") String envName);

    @SqlQuery("""
            SELECT env_name FROM agent_config_envs
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND blueprint_id = :blueprint_id
                AND ended_at IS NULL
            """)
    List<String> getEnvsByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("""
            SELECT
                b.id,
                b.project_id,
                b.name,
                b.type,
                b.description,
                b.`values`,
                b.created_by,
                b.created_at,
                b.last_updated_by,
                b.last_updated_at,
                GROUP_CONCAT(e.env_name) as envs
            FROM agent_blueprints b
            LEFT JOIN agent_config_envs e
                ON e.workspace_id = b.workspace_id
                AND e.project_id = b.project_id
                AND e.blueprint_id = b.id
                AND e.ended_at IS NULL
            WHERE b.workspace_id = :workspace_id
                AND b.project_id = :project_id
                AND b.type = 'blueprint'
            GROUP BY b.id, b.project_id, b.name, b.type, b.description, b.`values`,
                     b.created_by, b.created_at, b.last_updated_by, b.last_updated_at
            ORDER BY b.created_at DESC, b.id DESC
            LIMIT :limit OFFSET :offset
            """)
    List<AgentBlueprint> getBlueprintHistory(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("""
            SELECT COUNT(*) FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND type = 'blueprint'
            """)
    long countBlueprints(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId);

    @SqlQuery("""
            SELECT id, project_id, env_name, blueprint_id, created_by, created_at, ended_at
            FROM agent_config_envs
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND env_name IN (<env_names>)
                AND ended_at IS NULL
            """)
    List<AgentConfigEnv> getEnvsByNames(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @BindList("env_names") List<String> envNames);

    @SqlBatch("""
            INSERT INTO agent_config_envs (
                id,
                workspace_id,
                project_id,
                config_id,
                env_name,
                blueprint_id,
                created_by
            )
            VALUES (
                :bean.id,
                :workspace_id,
                :project_id,
                :config_id,
                :bean.envName,
                :bean.blueprintId,
                :created_by
            )
            """)
    void batchInsertEnvs(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("config_id") UUID configId,
            @Bind("created_by") String createdBy,
            @BindMethods("bean") List<AgentConfigEnv> envs);

    @SqlUpdate("""
            UPDATE agent_config_envs
            SET ended_at = CURRENT_TIMESTAMP(6)
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND env_name = :env_name
                AND ended_at IS NULL
            """)
    int closeEnvByName(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("env_name") String envName);

    @SqlUpdate("""
            UPDATE agent_config_envs
            SET ended_at = CURRENT_TIMESTAMP(6)
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id IN (<ids>)
                AND ended_at IS NULL
            """)
    void batchCloseEnvs(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @BindList("ids") List<UUID> ids);

    class BlueprintRowMapper implements RowMapper<AgentBlueprint> {

        private static final TypeReference<List<AgentConfigValue>> VALUES_TYPE_REF = new TypeReference<>() {
        };

        @Override
        public AgentBlueprint map(ResultSet rs, StatementContext ctx) throws SQLException {
            List<String> envs = null;
            if (RowUtils.hasColumn(rs, "envs")) {
                String envsString = rs.getString("envs");
                if (StringUtils.isNotBlank(envsString)) {
                    envs = Arrays.asList(envsString.split(","));
                }
            }

            List<AgentConfigValue> values = JsonUtils.readValue(rs.getString("values"), VALUES_TYPE_REF);

            var typeMapper = ctx.findColumnMapperFor(BlueprintType.class);
            if (typeMapper.isEmpty()) {
                throw new IllegalStateException("BlueprintType column mapper not found");
            }
            BlueprintType type = typeMapper.get().map(rs, "type", ctx);

            return AgentBlueprint.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .projectId(UUID.fromString(rs.getString("project_id")))
                    .name(rs.getString("name"))
                    .type(type)
                    .description(rs.getString("description"))
                    .envs(envs)
                    .values(values)
                    .createdBy(rs.getString("created_by"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                    .build();
        }
    }

}
