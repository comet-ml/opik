package com.comet.opik.domain;

import com.comet.opik.domain.OptimizerBlueprint.BlueprintType;
import com.comet.opik.infrastructure.db.BlueprintTypeArgumentFactory;
import com.comet.opik.infrastructure.db.BlueprintTypeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.infrastructure.db.ValueTypeArgumentFactory;
import com.comet.opik.infrastructure.db.ValueTypeColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(OptimizerConfig.class)
@RegisterConstructorMapper(OptimizerBlueprint.class)
@RegisterConstructorMapper(OptimizerConfigValue.class)
@RegisterConstructorMapper(OptimizerConfigEnv.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(ValueTypeArgumentFactory.class)
@RegisterColumnMapper(ValueTypeColumnMapper.class)
@RegisterArgumentFactory(BlueprintTypeArgumentFactory.class)
@RegisterColumnMapper(BlueprintTypeColumnMapper.class)
interface OptimizerConfigDAO {

    @SqlQuery("SELECT id, project_id, created_by, created_at, last_updated_by, last_updated_at " +
            "FROM optimizer_config " +
            "WHERE workspace_id = :workspace_id")
    OptimizerConfig getConfigByWorkspaceId(@Bind("workspace_id") String workspaceId);

    @SqlQuery("SELECT id, project_id, created_by, created_at, last_updated_by, last_updated_at " +
            "FROM optimizer_config " +
            "WHERE workspace_id = :workspace_id AND project_id = :project_id")
    OptimizerConfig getConfigByProjectId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId);

    @SqlQuery("SELECT config_id FROM optimizer_blueprint " +
            "WHERE workspace_id = :workspace_id AND id = :blueprint_id")
    UUID getConfigIdByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlUpdate("INSERT INTO optimizer_config (id, workspace_id, project_id, created_by, last_updated_by) " +
            "VALUES (:id, :workspace_id, :project_id, :created_by, :last_updated_by)")
    void insertConfig(
            @Bind("id") UUID id,
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("created_by") String createdBy,
            @Bind("last_updated_by") String lastUpdatedBy);

    @SqlUpdate("INSERT INTO optimizer_blueprint (id, workspace_id, project_id, config_id, type, description, created_by, last_updated_by) "
            +
            "VALUES (:id, :workspace_id, :project_id, :config_id, :type, :description, :created_by, :last_updated_by)")
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
            INSERT INTO optimizer_config_values (
                id,
                workspace_id,
                project_id,
                config_id,
                `key`,
                value,
                type,
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
                :bean.validFromBlueprintId,
                NULL
            )
            """)
    void batchInsertValues(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("config_id") UUID configId,
            @BindMethods("bean") List<OptimizerConfigValue> values);

    @SqlUpdate("UPDATE optimizer_config_values " +
            "SET valid_to_blueprint_id = :valid_to_blueprint_id " +
            "WHERE workspace_id = :workspace_id AND project_id = :project_id " +
            "AND valid_to_blueprint_id IS NULL " +
            "AND `key` IN (<keys>)")
    void closeValuesForKeys(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("valid_to_blueprint_id") UUID validToBlueprintId,
            @BindList("keys") List<String> keys);

    @SqlQuery("SELECT id, type, description, created_by, created_at, last_updated_by, last_updated_at " +
            "FROM optimizer_blueprint " +
            "WHERE workspace_id = :workspace_id AND project_id = :project_id " +
            "ORDER BY created_at DESC LIMIT 1")
    OptimizerBlueprint getLatestBlueprint(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId);

    @SqlQuery("SELECT id, project_id, type, description, created_by, created_at, last_updated_by, last_updated_at " +
            "FROM optimizer_blueprint " +
            "WHERE workspace_id = :workspace_id AND id = :blueprint_id")
    OptimizerBlueprint getBlueprintById(
            @Bind("workspace_id") String workspaceId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("SELECT project_id FROM optimizer_blueprint " +
            "WHERE workspace_id = :workspace_id AND id = :blueprint_id")
    UUID getProjectIdByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("SELECT blueprint_id FROM optimizer_config_envs " +
            "WHERE workspace_id = :workspace_id AND project_id = :project_id AND env_name = :env_name")
    UUID getBlueprintIdByEnvName(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("env_name") String envName);

    @SqlQuery("SELECT `key`, value, type FROM optimizer_config_values " +
            "WHERE workspace_id = :workspace_id AND project_id = :project_id " +
            "AND valid_from_blueprint_id <= :blueprint_id " +
            "AND (valid_to_blueprint_id IS NULL OR valid_to_blueprint_id > :blueprint_id)")
    java.util.List<OptimizerConfigValue> getValuesByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("SELECT `key`, value, type FROM optimizer_config_values " +
            "WHERE workspace_id = :workspace_id AND project_id = :project_id " +
            "AND valid_from_blueprint_id = :blueprint_id")
    List<OptimizerConfigValue> getValuesDeltaByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("SELECT env_name FROM optimizer_config_envs " +
            "WHERE workspace_id = :workspace_id AND project_id = :project_id AND blueprint_id = :blueprint_id")
    List<String> getTagsByBlueprintId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("blueprint_id") UUID blueprintId);

    @SqlQuery("SELECT id, project_id, env_name, blueprint_id, created_by, created_at, last_updated_by, last_updated_at "
            +
            "FROM optimizer_config_envs " +
            "WHERE workspace_id = :workspace_id AND project_id = :project_id AND env_name = :env_name")
    OptimizerConfigEnv getEnvByName(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("env_name") String envName);

    @SqlQuery("SELECT id, project_id, env_name, blueprint_id, created_by, created_at, last_updated_by, last_updated_at "
            +
            "FROM optimizer_config_envs " +
            "WHERE workspace_id = :workspace_id AND project_id = :project_id AND env_name IN (<env_names>)")
    List<OptimizerConfigEnv> getEnvsByNames(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @BindList("env_names") List<String> envNames);

    @SqlBatch("""
            INSERT INTO optimizer_config_envs (
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
            @BindMethods("bean") List<OptimizerConfigEnv> envs);

    @SqlBatch("""
            UPDATE optimizer_config_envs
            SET blueprint_id = :bean.blueprintId,
                last_updated_by = :last_updated_by,
                last_updated_at = CURRENT_TIMESTAMP(6)
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND env_name = :bean.envName
            """)
    void batchUpdateEnvs(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("last_updated_by") String lastUpdatedBy,
            @BindMethods("bean") List<OptimizerConfigEnv> envs);
}
