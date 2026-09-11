package com.comet.opik.domain;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.FeedbackDefinitionModel.FeedbackType;

@RegisterRowMapper(FeedbackDefinitionRowMapper.class)
@RegisterConstructorMapper(NumericalFeedbackDefinitionDefinitionModel.class)
@RegisterConstructorMapper(CategoricalFeedbackDefinitionDefinitionModel.class)
@RegisterConstructorMapper(BooleanFeedbackDefinitionDefinitionModel.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
public interface FeedbackDefinitionDAO {

    @SqlUpdate("INSERT INTO feedback_definitions(id, name, description, `type`, details, workspace_id, created_by, last_updated_by) VALUES (:feedback.id, :feedback.name, :feedback.description, :feedback.type, :feedback.details, :workspaceId, :feedback.createdBy, :feedback.lastUpdatedBy)")
    <T> void save(@Bind("workspaceId") String workspaceId,
            final @BindMethods("feedback") FeedbackDefinitionModel<T> feedback);

    @SqlUpdate("UPDATE feedback_definitions SET name = :feedback.name, description = :feedback.description, `type` = :feedback.type, details = :feedback.details, last_updated_by = :feedback.lastUpdatedBy WHERE id = :id AND workspace_id = :workspaceId")
    <T> void update(@Bind("id") UUID id, @BindMethods("feedback") FeedbackDefinitionModel<T> feedback,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM feedback_definitions WHERE id = :id AND workspace_id = :workspaceId")
    Optional<FeedbackDefinitionModel<?>> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM feedback_definitions WHERE id IN (<ids>) AND workspace_id = :workspace_id")
    List<FeedbackDefinitionModel<?>> findByIds(@BindList("ids") Set<UUID> ids,
            @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM feedback_definitions WHERE id = :id AND workspace_id = :workspaceId")
    void delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM feedback_definitions WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT COUNT(*) FROM feedback_definitions " +
            " WHERE workspace_id = :workspaceId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(type)> AND type = :type <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspaceId") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("type") @Bind("type") FeedbackType type);

    @SqlQuery("SELECT * FROM feedback_definitions " +
            " WHERE workspace_id = :workspaceId " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(type)> AND type = :type <endif> " +
            " ORDER BY id DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<FeedbackDefinitionModel<?>> find(@Bind("limit") int limit,
            @Bind("offset") int offset,
            @Bind("workspaceId") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("type") @Bind("type") FeedbackType type);

    @SqlQuery("SELECT COUNT(*) FROM feedback_definitions WHERE id IN (<ids>) AND workspace_id = :workspaceId AND name = :name")
    long containsNameByIds(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId,
            @Bind("name") String name);
}
