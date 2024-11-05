package com.comet.opik.domain;

import com.comet.opik.api.Prompt;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(Prompt.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface PromptDAO {

    @SqlUpdate("INSERT INTO prompts (id, name, description, created_by, last_updated_by, workspace_id) " +
            "VALUES (:bean.id, :bean.name, :bean.description, :bean.createdBy, :bean.lastUpdatedBy, :workspace_id)")
    void save(@Bind("workspace_id") String workspaceId, @BindMethods("bean") Prompt prompt);

    @SqlQuery("SELECT * FROM prompts WHERE id = :id AND workspace_id = :workspace_id")
    Prompt findById(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlQuery("SELECT * FROM prompts " +
            " WHERE workspace_id = :workspace_id " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " ORDER BY id DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Prompt> find(@Define("name") @Bind("name") String name, @Bind("workspace_id") String workspaceId,
            @Bind("offset") int offset, @Bind("limit") int limit);

    @SqlQuery("SELECT COUNT(id) FROM prompts " +
            " WHERE workspace_id = :workspace_id " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long count(@Define("name") @Bind("name") String name, @Bind("workspace_id") String workspaceId);

    @SqlQuery("SELECT * FROM prompts WHERE name = :name AND workspace_id = :workspace_id")
    Prompt findByName(@Bind("name") String name, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("UPDATE prompts SET name = :bean.name, description = :bean.description, last_updated_by = :bean.lastUpdatedBy "
            +
            " WHERE id = :bean.id AND workspace_id = :workspace_id")
    int update(@Bind("workspace_id") String workspaceId, @BindMethods("bean") Prompt updatedPrompt);

    @SqlUpdate("DELETE FROM prompts WHERE id = :id AND workspace_id = :workspace_id")
    int delete(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

}
