package com.comet.opik.domain;

import com.comet.opik.domain.AgentBlueprint.BlueprintType;
import com.comet.opik.infrastructure.db.BlueprintTypeArgumentFactory;
import com.comet.opik.infrastructure.db.BlueprintTypeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.infrastructure.db.ValueTypeArgumentFactory;
import com.comet.opik.infrastructure.db.ValueTypeColumnMapper;
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
@RegisterConstructorMapper(AgentBlueprint.class)
@RegisterConstructorMapper(AgentConfigValue.class)
@RegisterConstructorMapper(AgentConfigEnv.class)
@RegisterConstructorMapper(AgentConfigDAO.BlueprintProject.class)
@RegisterRowMapper(AgentConfigDAO.BlueprintWithEnvsRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(ValueTypeArgumentFactory.class)
@RegisterColumnMapper(ValueTypeColumnMapper.class)
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
            INSERT INTO agent_blueprints (id, workspace_id, project_id, config_id, type, description, created_by, last_updated_by)
            VALUES (:id, :workspace_id, :project_id, :config_id, :type, :description, :created_by, :last_updated_by)
            """)
    void insertBlueprint(
            @Bind("id") UUID id,
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("config_id") UUID configId,
            @Bind("type") BlueprintType type,
            @Bind("description") String description,
            @Bind("created_by") String createdBy,
            @Bind("last_updated_by") String lastUpdatedBy);

    @SqlBatch("""
            INSERT INTO agent_config_values (
                id,
                workspace_id,
                project_id,
                config_id,
                `key`,
                value,
                type,
                description,
                valid_from_blueprint_id,
                valid_to_blueprint_id
            )
            VALUES (
                :bean.id,
                :workspace_id,
                :project_id,
                :config_id,
                :bean.key,
                :bean.value,
                :bean.type,
                :bean.description,
                :bean.validFromBlueprintId,
                NULL
            )
            """)
    void batchInsertValues(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("config_id") UUID configId,
            @BindMethods("bean") List<AgentConfigValue> values);

    @SqlUpdate("""
            UPDATE agent_config_values
            SET valid_to_blueprint_id = :valid_to_blueprint_id
            WHERE workspace_id = :workspace_id AND project_id = :project_id
                AND valid_to_blueprint_id IS NULL
                AND `key` IN (<keys>)
            """)
    void closeValuesForKeys(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("valid_to_blueprint_id") UUID validToBlueprintId,
            @BindList("keys") List<String> keys);

    @SqlQuery("""
            SELECT id, project_id, type, description, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND type = :type
            ORDER BY id DESC LIMIT 1
            """)
    AgentBlueprint getLatestBlueprint(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("type") BlueprintType type);

    @SqlQuery("""
            SELECT id, project_id, type, description, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND id = :blueprint_id
            """)
    AgentBlueprint getBlueprintById(
            @Bind("workspace_id") String workspaceId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("""
            SELECT id, project_id, type, description, created_by, created_at, last_updated_by, last_updated_at
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
            SELECT project_id FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND id = :blueprint_id
            """)
    UUID getProjectIdByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("""
            SELECT id, project_id FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND id IN (<blueprint_ids>)
            """)
    List<BlueprintProject> getBlueprintsByIds(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @BindList("blueprint_ids") List<UUID> blueprintIds);

    @SqlQuery("""
            SELECT blueprint_id FROM agent_config_envs
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND env_name = :env_name
            """)
    UUID getBlueprintIdByEnvName(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("env_name") String envName);

    @SqlQuery("""
            SELECT v.*
            FROM agent_config_values v
            JOIN agent_blueprints b
                ON b.id = v.valid_from_blueprint_id
                AND b.workspace_id = v.workspace_id
                AND b.project_id = v.project_id
            WHERE v.workspace_id = :workspace_id
                AND v.project_id = :project_id
                AND v.valid_from_blueprint_id <= :blueprint_id
                AND (v.valid_to_blueprint_id IS NULL OR v.valid_to_blueprint_id > :blueprint_id)
                AND b.type = 'blueprint'
            """)
    List<AgentConfigValue> getValuesByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("""
            SELECT * FROM agent_config_values
            WHERE workspace_id = :workspace_id AND project_id = :project_id
                AND valid_from_blueprint_id = :blueprint_id
            """)
    List<AgentConfigValue> getValuesDeltaByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("""
            SELECT env_name FROM agent_config_envs
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND blueprint_id = :blueprint_id
            """)
    List<String> getEnvsByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("""
            SELECT
                b.id,
                b.project_id,
                b.type,
                b.description,
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
            WHERE b.workspace_id = :workspace_id
                AND b.project_id = :project_id
                AND b.type = 'blueprint'
            GROUP BY b.id, b.project_id, b.type, b.description, b.created_by, b.created_at, b.last_updated_by, b.last_updated_at
            ORDER BY b.id DESC
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
            SELECT id, project_id, env_name, blueprint_id, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_config_envs
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND env_name IN (<env_names>)
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
                created_by,
                last_updated_by
            )
            VALUES (
                :bean.id,
                :workspace_id,
                :project_id,
                :config_id,
                :bean.envName,
                :bean.blueprintId,
                :created_by,
                :last_updated_by
            )
            """)
    void batchInsertEnvs(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("config_id") UUID configId,
            @Bind("created_by") String createdBy,
            @Bind("last_updated_by") String lastUpdatedBy,
            @BindMethods("bean") List<AgentConfigEnv> envs);

    @SqlBatch("""
            UPDATE agent_config_envs
            SET blueprint_id = :bean.blueprintId,
                last_updated_by = :last_updated_by
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND env_name = :bean.envName
            """)
    void batchUpdateEnvs(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("last_updated_by") String lastUpdatedBy,
            @BindMethods("bean") List<AgentConfigEnv> envs);

    class BlueprintWithEnvsRowMapper implements RowMapper<AgentBlueprint> {

        @Override
        public AgentBlueprint map(ResultSet rs, StatementContext ctx) throws SQLException {
            List<String> envs = null;
            try {
                String envsString = rs.getString("envs");
                if (StringUtils.isNotBlank(envsString)) {
                    envs = Arrays.asList(envsString.split(","));
                }
            } catch (SQLException e) {
                // envs column doesn't exist in non-history queries, which is expected
            }

            var typeMapper = ctx.findColumnMapperFor(BlueprintType.class);
            if (typeMapper.isEmpty()) {
                throw new IllegalStateException("BlueprintType column mapper not found");
            }
            BlueprintType type = typeMapper.get().map(rs, "type", ctx);

            return AgentBlueprint.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .projectId(UUID.fromString(rs.getString("project_id")))
                    .type(type)
                    .description(rs.getString("description"))
                    .envs(envs)
                    .createdBy(rs.getString("created_by"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                    .build();
        }
    }
}
