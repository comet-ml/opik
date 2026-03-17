package com.comet.opik.domain;

import com.comet.opik.domain.AgentBlueprint.BlueprintType;
import com.comet.opik.infrastructure.db.BlueprintTypeArgumentFactory;
import com.comet.opik.infrastructure.db.BlueprintTypeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.infrastructure.db.ValueTypeArgumentFactory;
import com.comet.opik.infrastructure.db.ValueTypeColumnMapper;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RowUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Builder;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RegisterConstructorMapper(AgentConfig.class)
@RegisterConstructorMapper(AgentBlueprint.class)
@RegisterConstructorMapper(AgentConfigValue.class)
@RegisterConstructorMapper(AgentConfigEnv.class)
@RegisterConstructorMapper(AgentConfigDAO.BlueprintProject.class)
@RegisterConstructorMapper(AgentConfigDAO.BlueprintValueReference.class)
@RegisterRowMapper(AgentConfigDAO.BlueprintWithEnvsRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(ValueTypeArgumentFactory.class)
@RegisterColumnMapper(ValueTypeColumnMapper.class)
@RegisterArgumentFactory(BlueprintTypeArgumentFactory.class)
@RegisterColumnMapper(BlueprintTypeColumnMapper.class)
interface AgentConfigDAO {

    record BlueprintProject(UUID id, UUID projectId) {
    }

    @Builder(toBuilder = true)
    record BlueprintValueReference(@NonNull UUID blueprintId, @NonNull UUID projectId, @NonNull UUID configId,
            @NonNull String configKey, String oldValue, @NonNull String latestBlueprintName) {
    }

    @Builder(toBuilder = true)
    record BlueprintInsertData(@NonNull UUID id, @NonNull UUID projectId, @NonNull UUID configId,
            @NonNull BlueprintType type, @NonNull String name, String description) {
    }

    @Builder(toBuilder = true)
    record ValueCloseRef(@NonNull UUID projectId, @NonNull UUID validToBlueprintId, @NonNull String key) {
    }

    @Builder(toBuilder = true)
    record ValueInsertData(@NonNull UUID id, @NonNull UUID projectId, @NonNull UUID configId,
            @NonNull String key, @NonNull String value, @NonNull AgentConfigValue.ValueType type,
            String description, @NonNull UUID validFromBlueprintId) {
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
            INSERT INTO agent_blueprints (id, workspace_id, project_id, config_id, type, name, description, created_by, last_updated_by)
            VALUES (:id, :workspace_id, :project_id, :config_id, :type, :name, :description, :created_by, :last_updated_by)
            """)
    void insertBlueprint(
            @Bind("id") UUID id,
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("config_id") UUID configId,
            @Bind("type") BlueprintType type,
            @Bind("name") String name,
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

    @SqlBatch("""
            UPDATE agent_config_values
            SET valid_to_blueprint_id = :bean.validToBlueprintId
            WHERE workspace_id = :workspace_id AND project_id = :bean.projectId
                AND valid_to_blueprint_id IS NULL
                AND `key` = :bean.key
            """)
    void batchCloseValuesByKey(
            @Bind("workspace_id") String workspaceId,
            @BindMethods("bean") List<ValueCloseRef> refs);

    @SqlBatch("""
            INSERT INTO agent_blueprints (id, workspace_id, project_id, config_id, type, name, description, created_by, last_updated_by)
            VALUES (:bean.id, :workspace_id, :bean.projectId, :bean.configId, :bean.type, :bean.name, :bean.description, :created_by, :last_updated_by)
            """)
    void batchInsertBlueprints(
            @Bind("workspace_id") String workspaceId,
            @Bind("created_by") String createdBy,
            @Bind("last_updated_by") String lastUpdatedBy,
            @BindMethods("bean") List<BlueprintInsertData> blueprints);

    @SqlBatch("""
            INSERT INTO agent_config_values (
                id, workspace_id, project_id, config_id,
                `key`, value, type, description,
                valid_from_blueprint_id, valid_to_blueprint_id
            )
            VALUES (
                :bean.id, :workspace_id, :bean.projectId, :bean.configId,
                :bean.key, :bean.value, :bean.type, :bean.description,
                :bean.validFromBlueprintId, NULL
            )
            """)
    void batchInsertValuesMultiProject(
            @Bind("workspace_id") String workspaceId,
            @BindMethods("bean") List<ValueInsertData> values);

    @SqlQuery("""
            SELECT id, project_id, name, type, description, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND type = :type
            ORDER BY id DESC LIMIT 1
            """)
    AgentBlueprint getLatestBlueprint(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("type") BlueprintType type);

    @SqlQuery("""
            SELECT id, project_id, name, type, description, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_blueprints
            WHERE workspace_id = :workspace_id AND id = :blueprint_id
            """)
    AgentBlueprint getBlueprintById(
            @Bind("workspace_id") String workspaceId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("""
            SELECT id, project_id, name, type, description, created_by, created_at, last_updated_by, last_updated_at
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
            SELECT id, project_id, name, type, description, created_by, created_at, last_updated_by, last_updated_at
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
                AND ended_at IS NULL
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
                AND ended_at IS NULL
            """)
    List<String> getEnvsByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("""
            WITH prompt_commits AS (
                SELECT pv.commit
                FROM prompt_versions pv
                WHERE pv.workspace_id = :workspace_id
                    AND pv.prompt_id = :prompt_id
                    AND pv.commit != :new_commit
            )
            SELECT DISTINCT
                ab.id as blueprint_id,
                ab.project_id,
                ab.config_id,
                acv.key as config_key,
                acv.value as old_value,
                ab.name as latest_blueprint_name
            FROM agent_blueprints ab
            JOIN agent_config_values acv
                ON acv.valid_from_blueprint_id = ab.id
                AND acv.workspace_id = ab.workspace_id
                AND acv.project_id = ab.project_id
            WHERE ab.workspace_id = :workspace_id
                AND ab.type = 'blueprint'
                AND acv.type = 'prompt'
                AND acv.valid_to_blueprint_id IS NULL
                AND acv.value IN (SELECT commit FROM prompt_commits)
                <if(exclude_project_ids)> AND ab.project_id NOT IN (<exclude_project_ids>) <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<BlueprintValueReference> findProjectsWithOutdatedPromptReferences(
            @Bind("workspace_id") String workspaceId,
            @Bind("prompt_id") UUID promptId,
            @Bind("new_commit") String newCommit,
            @Define("exclude_project_ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "exclude_project_ids") Set<UUID> excludeProjectIds);

    @SqlQuery("""
            SELECT
                bh.id,
                bh.project_id,
                bh.name,
                bh.type,
                bh.description,
                bh.created_by,
                bh.created_at,
                bh.last_updated_by,
                bh.last_updated_at,
                bh.envs,
                IF(MAX(v.id) IS NOT NULL,
                    JSON_ARRAYAGG(JSON_OBJECT(
                        'id', v.id,
                        'project_id', v.project_id,
                        'key', v.`key`,
                        'value', v.value,
                        'type', v.type,
                        'description', v.description,
                        'valid_from_blueprint_id', v.valid_from_blueprint_id,
                        'valid_to_blueprint_id', v.valid_to_blueprint_id
                    )),
                    NULL
                ) as delta_values
            FROM (
                SELECT
                    b.id,
                    b.project_id,
                    b.workspace_id,
                    b.name,
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
                    AND e.ended_at IS NULL
                WHERE b.workspace_id = :workspace_id
                    AND b.project_id = :project_id
                    AND b.type = 'blueprint'
                GROUP BY b.id, b.project_id, b.workspace_id, b.name, b.type, b.description,
                         b.created_by, b.created_at, b.last_updated_by, b.last_updated_at
                ORDER BY b.id DESC
                LIMIT :limit OFFSET :offset
            ) bh
            LEFT JOIN agent_config_values v
                ON v.workspace_id = bh.workspace_id
                AND v.project_id = bh.project_id
                AND v.valid_from_blueprint_id = bh.id
            GROUP BY bh.id, bh.project_id, bh.name, bh.type, bh.description,
                     bh.created_by, bh.created_at, bh.last_updated_by, bh.last_updated_at, bh.envs
            ORDER BY bh.id DESC
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
                AND id IN (<ids>)
                AND ended_at IS NULL
            """)
    void batchCloseEnvs(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @BindList("ids") List<UUID> ids);

    class BlueprintWithEnvsRowMapper implements RowMapper<AgentBlueprint> {

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

            List<AgentConfigValue> values = null;
            if (RowUtils.hasColumn(rs, "delta_values")) {
                String deltaValuesJson = rs.getString("delta_values");
                if (StringUtils.isNotBlank(deltaValuesJson)) {
                    values = JsonUtils.readValue(deltaValuesJson, VALUES_TYPE_REF);
                }
            }

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
