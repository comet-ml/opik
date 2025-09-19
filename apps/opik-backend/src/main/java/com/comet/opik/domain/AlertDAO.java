package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterConstructorMapper(Alert.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AlertDAO {

    @SqlUpdate("INSERT INTO alerts (id, name, description, condition_type, threshold_value, project_id, workspace_id, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :bean.name, :bean.description, :bean.conditionType, :bean.thresholdValue, :bean.projectId, :workspaceId, :bean.createdBy, :bean.lastUpdatedBy)")
    @GetGeneratedKeys
    Alert save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Alert alert);

    @SqlUpdate("UPDATE alerts SET " +
            "name = COALESCE(:name, name), " +
            "description = COALESCE(:description, description), " +
            "condition_type = COALESCE(:conditionType, condition_type), " +
            "threshold_value = COALESCE(:thresholdValue, threshold_value), " +
            "project_id = COALESCE(:projectId, project_id), " +
            "last_updated_by = :lastUpdatedBy " +
            "WHERE id = :id AND workspace_id = :workspaceId")
    void update(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("name") String name,
            @Bind("description") String description,
            @Bind("conditionType") String conditionType,
            @Bind("thresholdValue") java.math.BigDecimal thresholdValue,
            @Bind("projectId") UUID projectId,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlUpdate("DELETE FROM alerts WHERE id = :id AND workspace_id = :workspaceId")
    void delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM alerts WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM alerts WHERE id = :id AND workspace_id = :workspaceId")
    Alert findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM alerts WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    List<Alert> findByIds(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM alerts WHERE workspace_id = :workspaceId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(conditionType)> AND condition_type = :conditionType <endif> " +
            " <if(projectId)> AND project_id = :projectId <endif> " +
            " ORDER BY <if(sort_fields)> <sort_fields>, <endif> created_at DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Alert> find(@Bind("limit") int limit,
            @Bind("offset") int offset,
            @Bind("workspaceId") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("conditionType") @Bind("conditionType") String conditionType,
            @Define("projectId") @Bind("projectId") UUID projectId,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields);

    @SqlQuery("SELECT COUNT(*) FROM alerts " +
            " WHERE workspace_id = :workspaceId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(conditionType)> AND condition_type = :conditionType <endif> " +
            " <if(projectId)> AND project_id = :projectId <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspaceId") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("conditionType") @Bind("conditionType") String conditionType,
            @Define("projectId") @Bind("projectId") UUID projectId);

    @SqlQuery("SELECT * FROM alerts WHERE project_id = :projectId AND workspace_id = :workspaceId")
    List<Alert> findByProjectId(@Bind("projectId") UUID projectId, @Bind("workspaceId") String workspaceId);

    default Optional<Alert> fetch(UUID id, String workspaceId) {
        return Optional.ofNullable(findById(id, workspaceId));
    }
}
